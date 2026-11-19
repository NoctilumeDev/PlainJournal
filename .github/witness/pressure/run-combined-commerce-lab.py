"""Keep one real checkout linked across browser, midnight, cancellation and refund."""
import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("browser_lab", Path(os.environ["PJ_BROWSER_LAB_SCRIPT"]))
browser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browser)
price = browser.price
base = price.base
BEFORE = datetime(2030, 1, 1, 15, 59, 59, tzinfo=timezone.utc)
AFTER = datetime(2030, 1, 1, 16, 0, 1, tzinfo=timezone.utc)
NETWORK_PREFIX = "172.31.251."
ROLLOVER_HOSTS = {"trade": 23, "inventory": 21, "payment": 24, "marketing": 22}


class Combined(browser.BrowserLab):
    def fixture_network_options(self):
        return ["--subnet", NETWORK_PREFIX + "0/24"]

    def container(self, suffix, image, options, command):
        app = suffix.split("-", 1)[0]
        if app in ROLLOVER_HOSTS:
            options = [*options, "--ip", NETWORK_PREFIX + str(ROLLOVER_HOSTS[app])]
        return super().container(suffix, image, options, command)

    def service(self, app, settings, fixture_name=None):
        if app in {"trade", "inventory", "payment", "marketing", "fulfillment"}:
            settings = {**settings, "spring.datasource.hikari.connection-init-sql":
                        f"SET SESSION timestamp = {int(self.phase_timestamp.timestamp())}"}
        super().service(app, settings, fixture_name)

    def owned_signal(self, name):
        path = self.directory / (name + ".json")
        failure = self.directory / "combination-browser-failure.json"
        until = time.monotonic() + self.args.hold_seconds
        while time.monotonic() < until:
            if failure.is_file():
                raise RuntimeError("browser reported a failure")
            if path.is_file():
                data = json.loads(path.read_text())
                assert data["source"] == self.result["source"]
                return data
            time.sleep(0.5)
        raise TimeoutError(name)

    def rollover(self):
        for app in ROLLOVER_HOSTS:
            container = self.apps[app]
            owner = self.docker("inspect", "--format", '{{index .Config.Labels "codex.task"}}', container)
            assert owner == self.ident
            self.stop(container)
            self.docker("rm", container)
            self.containers.remove(container)
        self.phase_timestamp = AFTER
        self.start_broker(self.broker_image)
        for topic in ("ecommerce-order-events",):
            assert "success" in self.admin("updateTopic", "-c", "EcommerceCluster", "-t", topic,
                                           "-r", "4", "-w", "4").lower()
        services = {
            "inventory": {f"ecommerce.inventory.{feature}.enabled": "false"
                          for feature in ("outbox", "order-consumer", "return-consumer", "reconciliation")},
            "payment": price.PAYMENT_SETTINGS,
            "trade": {**price.REDIS_SETTINGS, **price.TRADE_SETTINGS, "ecommerce.trade.outbox.enabled": "true",
                      "ecommerce.trade.payment-consumer.enabled": "true", "ecommerce.trade.refund-result-consumer.enabled": "true"},
            "marketing": {**price.REDIS_SETTINGS, "ecommerce.marketing.flash-sale.redis-enabled": "false",
                          "ecommerce.marketing.flash-sale-outbox.enabled": "false",
                          "ecommerce.marketing.flash-sale-result-consumer.enabled": "false",
                          "ecommerce.marketing.order-consumer.enabled": "true"},
            "fulfillment": {"ecommerce.fulfillment.reconciliation.enabled": "false",
                            "ecommerce.fulfillment.geo.cache-enabled": "false",
                            "ecommerce.fulfillment.after-sale-consumer.enabled": "false"},
        }
        for app, settings in services.items():
            if app == "fulfillment":
                self.sql("CREATE DATABASE b1_fulfillment;")
                base.PORTS[app] = 18106
            self.service(app, settings, app + "-after")

    def topology(self, label):
        nodes = {"gateway": self.gateway_id, **self.apps}
        readback = {}
        for name, container in nodes.items():
            raw = json.loads(self.docker("inspect", container))[0]
            network = raw["NetworkSettings"]["Networks"][self.ident]
            readback[name] = {"id": container, "ip": network["IPAddress"], "running": raw["State"]["Running"]}
        self.save(label + "-network.json", readback)

    def post_close_diagnostics(self, order):
        probes = {}
        for name, app, path in (
            ("closed-order", "trade", "/api/v1/trade/orders/" + order),
            ("original-payment", "payment", "/api/v1/payment/payments/by-order/" + order),
        ):
            try:
                result = self.get(app, path, headers=self.customer)
                probes[name] = {"status": result["status"], "code": result["body"].get("code")}
            except Exception as exc:
                probes[name] = {"error": type(exc).__name__}
        probes["reorder_persisted"] = self.sql(
            "SELECT order_no,status FROM b1_trade.trade_order WHERE idempotency_key='COMPOUND-NEXT-ORDER';")
        for label, container in (("gateway", self.gateway_id), ("trade", self.apps["trade"]),
                                 ("marketing", self.apps["marketing"]), ("payment", self.apps["payment"])):
            logs = self.docker("logs", "--tail", "240", container, check=False)
            probes[label + "_exception_classes"] = sorted(set(re.findall(r"\b[\w.]+(?:Exception|Error)\b", logs)))
            probes[label + "_network_messages"] = sorted(set(re.findall(
                r"(?:Connection refused|Connection reset|No route to host|Name or service not known)[^\r\n]{0,95}",
                logs)))
        self.save("post-close-diagnostics.json", probes)

    def mock_payment_callback(self, payment_no, label):
        stamp = int(AFTER.timestamp())
        response = self.get("payment", "/api/v1/payment/callbacks/mock", self.signed({
            "paymentNo": payment_no, "externalEventId": "COMPOUND-PAY-SUCCESS",
            "externalTransactionNo": "COMPOUND-PAY-TXN", "status": "SUCCESS", "amount": price.OLD_PRICE,
            "timestamp": stamp,
        }, (payment_no, "COMPOUND-PAY-SUCCESS", "COMPOUND-PAY-TXN", "SUCCESS", str(price.OLD_PRICE), str(stamp))))
        data = self.data(label, response)
        assert data["paymentNo"] == payment_no and data["status"] == "SUCCESS"

    @staticmethod
    def signed(payload, fields):
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), "|".join(fields).encode(), hashlib.sha256)
        return payload | {"signature": signature.hexdigest()}

    def refund(self, order, payment_no):
        response = self.data("compound-refund-request", self.get("payment",
            f"/api/v1/payment/admin/payments/{payment_no}/exception-refunds",
            {"reason": "Synthetic cancellation before late settlement"},
            {**self.admin_headers, "Idempotency-Key": "COMPOUND-REFUND"}))
        number = response["refundNo"]
        assert response["amount"] == price.OLD_PRICE
        self.wait_for(lambda: (self.refund_state(order)["refund"] or {}).get("requestStatus") == "SENT",
                      45, "mock refund request acknowledgement")
        stamp = int(AFTER.timestamp())
        payload = self.signed({
            "refundNo": number, "externalEventId": "COMPOUND-REFUND-SUCCESS",
            "externalRefundNo": "COMPOUND-REFUND-TXN", "status": "SUCCESS", "amount": price.OLD_PRICE,
            "timestamp": stamp,
        }, (number, "COMPOUND-REFUND-SUCCESS", "COMPOUND-REFUND-TXN", "SUCCESS", str(price.OLD_PRICE), str(stamp)))
        for index in range(2):
            settled = self.data(f"refund-result-{index}", self.get("payment", "/api/v1/payment/callbacks/mock/refunds", payload))
            assert settled["status"] == "SUCCESS" and settled["refundNo"] == number
        self.wait_for(lambda: self.states(order)["orderState"] == "CLOSED", 90, "real Trade receipt of refund")

    def execute(self):
        self.phase_timestamp = BEFORE
        self.stage = "real-checkout-before-midnight"
        self.open_lab()
        self.setup_customer()
        self.setup_sku()
        order = self.checkout("compound-main-order", price.OLD_PRICE)
        self.service("payment", price.PAYMENT_SETTINGS, "payment-before")
        self.gateway()
        storefront = self.preview("storefront-web")
        self.host_check("host-before-browser.json")
        self.topology("before-midnight")
        self.save("workspace.json", {"url": storefront, "source": self.result["source"], "order": order,
                                    "product": self.product_id, "sku": self.sku_id, "address": self.address})
        self.stage = "two-authenticated-sessions-and-lost-response"
        before = self.owned_signal("browser-initial-intents")
        number = before["paymentNo"]
        facts = self.facts(order)
        assert facts["payments"] == 1 and facts["paymentNo"] == number
        assert facts["paymentState"] == "PROCESSING" and facts["transactions"] == 0
        self.save("before-midnight-financial-facts.json", facts)

        self.stage = "midnight-rollover-and-cancellation"
        self.rollover()
        self.topology("after-midnight")
        earlier = json.loads((self.directory / "before-midnight-network.json").read_text())
        later = json.loads((self.directory / "after-midnight-network.json").read_text())
        for app, host in ROLLOVER_HOSTS.items():
            assert earlier[app]["id"] != later[app]["id"]
            assert earlier[app]["ip"] == later[app]["ip"] == NETWORK_PREFIX + str(host)
        assert earlier["gateway"] == later["gateway"]
        canceled = self.data("compound-cancel-result", self.cancel(order))
        assert canceled["status"] == "CANCELED"
        assert self.states(order)["reservation"] == "RELEASED"
        self.stage = "late-payment-and-refund"
        self.mock_payment_callback(number, "compound-first-payment-callback")
        self.wait_for(lambda: self.states(order)["orderState"] == "PAYMENT_EXCEPTION", 80, "review for late settlement")
        self.refund(order, number)
        self.stage = "duplicate-callbacks-and-final-ledger"
        for index in range(2):
            self.mock_payment_callback(number, f"compound-late-payment-callback-{index}")
        facts = self.refund_state(order)
        assert facts["orderState"] == "CLOSED"
        assert facts["payment"]["payments"] == facts["payment"]["transactions"] == facts["payment"]["callbacks"] == 1
        assert facts["payment"]["successfulAmount"] == facts["refund"]["amountRefunded"] == price.OLD_PRICE
        assert facts["linkedPayment"] == facts["refund"]["originalPaymentNo"] == number
        assert facts["refund"]["transactions"] == facts["refund"]["refundReceipts"] == 1
        assert (facts["orderPaid"], facts["confirmations"], facts["stock"], facts["reserved"]) == (0, 0, 10, 0)
        assert self.sql(f"SELECT COUNT(*) FROM b1_fulfillment.fulfillment_order WHERE order_no='{order}'") == "0"
        stamps = self.sql(f"""
SELECT DATE_FORMAT(p.created_at,'%Y-%m-%d %H:%i:%s'),DATE_FORMAT(c.received_at,'%Y-%m-%d %H:%i:%s')
FROM b1_payment.payment_order p JOIN b1_payment.payment_callback_log c ON c.payment_no=p.payment_no
WHERE p.order_no='{order}';
""").split("\t")
        assert stamps == ["2030-01-01 15:59:59", "2030-01-01 16:00:01"]
        self.save("compound-four-ledger-final.json", {
            "facts": facts, "shipmentRecords": 0, "timezone": "+08:00", "midnightUtc": stamps,
        })
        self.stage = "zone-change-and-fresh-order"
        self.save("browser-readback-request.json", {"source": self.result["source"], "order": order, "paymentNo": number})
        try:
            followup = self.owned_signal("browser-new-order")
        except Exception:
            self.post_close_diagnostics(order)
            raise
        new = followup["newOrder"]
        assert new != order and new.startswith("ORD") and new[3:].isascii() and new[3:].isdigit()
        new_view = self.data("compound-new-order", self.order_state(new))
        assert new_view["status"] == "PENDING_PAYMENT" and new_view["totalAmount"] == price.OLD_PRICE
        balances = self.facts(new)
        assert balances["payments"] == balances["transactions"] == balances["refunds"] == 0
        assert self.states(new)["reserved"] == 1 and self.states(new)["stock"] == price.STOCK
        self.host_check("host-at-completion.json")
        self.result.update(status="PASS_SCOPED", old_order=order, new_order=new, payment_no=number,
                           cases=["two sessions and lost response", "midnight cancellation and late settlement",
                                  "refund and replay", "timezone change and checkout"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    parser.add_argument("--node", required=True)
    parser.add_argument("--hold-seconds", type=int, default=480)
    runner = Combined(parser.parse_args())
    try:
        runner.execute()
    except Exception as exc:
        runner.result.update(status="INCONCLUSIVE", stage=runner.stage, reason=type(exc).__name__)
    finally:
        runner.cleanup()
        runner.save("result.json", runner.result)
    print(json.dumps(runner.result))
    return 0 if runner.result["status"] == "PASS_SCOPED" and runner.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
