import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location("refund_cases", HERE / "verify-p0-04-exception-refund-staged-settlement.py")
refund = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refund)
base = refund.base
FULFILLMENT_JAR = "backend/services/fulfillment-service/target/fulfillment-service.jar"
FULFILLMENT_DIGEST = json.loads((INPUTS / "candidate-jars.json").read_text())[FULFILLMENT_JAR]
base.PORTS["fulfillment"] = 18106


class ShipmentCases(refund.RefundCase):
    def start_broker(self, image):
        self.namesrv = self.container("namesrv", image, [
            "--network-alias", "namesrv", "--memory", "300m", "--cpus", "1.5",
            "-e", "JAVA_OPT_EXT=-Xms32m -Xmx112m -Xmn32m -XX:ActiveProcessorCount=2",
        ], ["sh", "mqnamesrv"])
        broker = self.directory / "broker-local.conf"
        broker.write_text(
            "brokerClusterName=EcommerceCluster\nbrokerName=broker-a\nbrokerId=0\n"
            "listenPort=10911\nbrokerRole=ASYNC_MASTER\n"
            "flushDiskType=ASYNC_FLUSH\nstorePathRootDir=/tmp/pj-store\n"
            "storePathCommitLog=/tmp/pj-store/commitlog\nmapedFileSizeCommitLog=16777216\n"
            "autoCreateTopicEnable=false\nautoCreateSubscriptionGroup=true\nenablePropertyFilter=true\n",
            encoding="utf-8")
        proxy = self.directory / "proxy-local.json"
        proxy.write_text(json.dumps({"grpcServerPort": 18082, "rocketMQClusterName": "EcommerceCluster",
                                     "useEndpointPortFromRequest": True}), encoding="utf-8")
        self.broker = self.container("broker", image, [
            "--network-alias", "broker", "--memory", "620m", "--cpus", "1.5",
            "-e", "NAMESRV_ADDR=namesrv:9876",
            "-e", "JAVA_OPT_EXT=-Xms96m -Xmx384m -Xmn64m -XX:ActiveProcessorCount=2",
            "--mount", f"type=bind,source={broker},target=/tmp/broker-local.conf,readonly",
            "--mount", f"type=bind,source={proxy},target=/tmp/proxy-local.json,readonly",
        ], ["sh", "mqbroker", "-n", "namesrv:9876", "-c", "/tmp/broker-local.conf",
            "-pc", "/tmp/proxy-local.json", "--enable-proxy"])
        self.proxy = self.broker
        self.ready_local_broker()
        self.host_check("host-broker-ready.json")
        output = self.admin("updateTopic", "-c", "EcommerceCluster", "-t", "ecommerce-payment-events",
                            "-r", "4", "-w", "4")
        assert "success" in output.lower()
        self.save("rocketmq-topic.json", {"topology": "rocketmq-5.3.2-local",
                                         "cluster": self.admin("clusterList"), "created": output})

    def ready_local_broker(self):
        self.wait_for(lambda: "EcommerceCluster" in self.admin("clusterList", check=False),
                      95, "local RocketMQ broker registration")
        self.wait_for(lambda: any(":" + f"{18082:04X}" in line and line.split()[3] == "0A"
                                   for line in self.docker("exec", self.broker, "cat", "/proc/net/tcp",
                                                           "/proc/net/tcp6").splitlines()),
                      75, "local RocketMQ gRPC listener")

        registration = self.admin("clusterList")
        assigned = json.loads(self.docker("inspect", "--format", "{{json .NetworkSettings.Networks}}", self.broker))
        ip = assigned[self.ident]["IPAddress"]
        assert ip and f"{ip}:10911" in registration
        self.save("broker-routable-registration.json", {"containerIp": ip, "registration": registration})

    def restart_broker(self):
        for cid in (self.namesrv, self.broker):
            self.docker("start", cid, timeout=35)
        self.ready_local_broker()
        self.save("local-broker-restart-readback.json",
                  {"cluster": self.admin("clusterList"),
                   "paymentTopic": self.admin("topicStatus", "-t", "ecommerce-payment-events")})

    def stop(self, cid):
        running = self.docker("inspect", "--format", "{{.State.Running}}", cid)
        if running == "true":
            super().stop(cid)


    def container(self, suffix, image, options, command):
        if suffix in {"trade-publisher", "trade-shipping", "trade-logistics", "fulfillment"}:
            command = ["-Xmx224m" if part == "-Xmx288m" else part for part in command]
        return super().container(suffix, image, options, command)

    def verify_late_payment(self, order):
        before = self.refund_state(order)
        payment_no = before["linkedPayment"]
        external = self.sql(f"""
SELECT t.channel_transaction_no FROM b1_payment.payment_transaction t
JOIN b1_payment.payment_order p ON t.payment_id=p.id
WHERE p.order_no='{order}' AND t.status='SUCCESS';
""")
        stamp = int(time.time())
        assert external == "FIXTURE-TXN-" + order
        for label, outcome, event in (
            ("late-failure", "FAILED", "P0-11-LATE-FAIL"),
            ("late-success", "SUCCESS", "P0-11-LATE-SUCCESS"),
            ("late-success-replay", "SUCCESS", "P0-11-LATE-SUCCESS"),
        ):
            message = "|".join((payment_no, event, external, outcome, "39.8", str(stamp)))
            signed = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
            request = {"paymentNo": payment_no, "externalEventId": event, "externalTransactionNo": external,
                       "status": outcome, "amount": 39.8, "timestamp": stamp, "signature": signed}
            result = self.get("payment", "/api/v1/payment/callbacks/mock", request)
            self.save(label + ".json", result)
            if outcome == "FAILED":
                assert result["status"] == 409 and result["body"]["code"] == "INVALID_STATE"
            else:
                assert result["status"] == 200 and result["body"]["data"]["paymentNo"] == payment_no
                assert result["body"]["data"]["status"] == "SUCCESS"
        after = self.refund_state(order)
        callback_facts = self.sql("""
SELECT CONCAT(external_event_id,':',processing_status,':',COALESCE(error_message,''))
FROM b1_payment.payment_callback_log WHERE external_event_id LIKE 'P0-11-LATE-%'
ORDER BY external_event_id;
""").splitlines()
        self.save("post-late-callbacks-ledgers.json", after)
        self.save("late-callback-audits.json", callback_facts)
        assert after["payment"]["callbacks"] == before["payment"]["callbacks"] + 2
        assert before["payment"] | {"callbacks": after["payment"]["callbacks"]} == after["payment"]
        assert before | {"payment": after["payment"]} == after

    def trade(self, suffix, **switches):
        features = (
            "outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
            "after-sale-fulfillment-consumer", "after-sale-inventory-consumer", "refund-result-consumer", "reconciliation"
        )
        self.service("trade", {
            **{f"ecommerce.trade.{feature}.enabled": "false" for feature in features},
            "ecommerce.trade.client.service-discovery-enabled": "false",
            "ecommerce.trade.order.recovery-enabled": "false",
            **switches,
        }, suffix)

    def apply_after_sale(self, order, key):
        return self.get("trade", "/api/v1/trade/orders/" + order + "/after-sales",
                        {"reason": "Isolated pre-completion after-sale boundary"}, self.headers(key))

    def shipment_row(self, order):
        state = self.sql(f"""
SELECT JSON_OBJECT(
 'id',fo.fulfillment_no,'status',fo.status,'tracking',fo.tracking_no,'carrier',fo.carrier,
 'order',fo.order_no,'shipEvent',(SELECT COUNT(*) FROM b1_fulfillment.outbox_event
           WHERE aggregate_id=fo.fulfillment_no AND event_type='ShipmentDispatched'),
 'shipEventStatus',(SELECT status FROM b1_fulfillment.outbox_event
           WHERE aggregate_id=fo.fulfillment_no AND event_type='ShipmentDispatched'),
 'shipReceipts',(SELECT COUNT(*) FROM b1_trade.consumed_event t
          JOIN b1_fulfillment.outbox_event f ON f.id=t.event_id
          WHERE f.aggregate_id=fo.fulfillment_no AND f.event_type='ShipmentDispatched'),
 'orderPaidReceipts',(SELECT COUNT(*) FROM b1_fulfillment.consumed_event f
          JOIN b1_trade.outbox_event t ON t.id=f.event_id
          WHERE t.aggregate_id=fo.order_no AND t.event_type='OrderPaid')
)
FROM b1_fulfillment.fulfillment_order fo WHERE fo.order_no='{order}';
""")
        return json.loads(state) if state else None

    def no_after_sale(self, label, response):
        self.save(label + ".json", response)
        assert (response["status"], response["body"]["code"]) == (409, "INVALID_STATE")

    def execute(self):
        super().execute()
        self.result.update(status="INCONCLUSIVE", prerequisite_refund_cases=self.result["cases"], cases=[])
        self.save("shipment-test-identity.json", {
            "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "refund_runner_sha256": hashlib.sha256(Path(refund.__file__).read_bytes()).hexdigest(),
            "fulfillment_jar_sha256": FULFILLMENT_DIGEST})
        paid, exception = refund.cancel.ORDERS
        self.stage = "late-callbacks-after-refund-close"
        self.verify_late_payment(exception)
        self.result["cases"].append("P0-11-late-and-replayed-after-close")
        self.stop(self.apps["payment"])
        self.stop(self.apps["trade"])

        self.stage = "publish-paid-control-to-real-fulfillment"
        assert hashlib.sha256((base.REPO / FULFILLMENT_JAR).read_bytes()).hexdigest() == FULFILLMENT_DIGEST
        self.jars[FULFILLMENT_JAR] = FULFILLMENT_DIGEST
        self.sql("CREATE DATABASE b1_fulfillment;")
        self.restart_broker()
        for topic in ("ecommerce-order-events", "ecommerce-logistics-events", "ecommerce-flash-sale-events"):
            result = self.admin("updateTopic", "-c", "EcommerceCluster", "-t", topic, "-r", "4", "-w", "4")
            assert "success" in result.lower()
        self.trade("trade-publisher", **{"ecommerce.trade.outbox.enabled": "true"})
        self.host_check("host-order-publish.json")
        self.wait_for(lambda: self.sql(f"""
SELECT COUNT(*) FROM b1_trade.outbox_event WHERE status='PUBLISHED' AND (
(aggregate_id='{paid}' AND event_type='OrderPaid') OR
(aggregate_id='{exception}' AND event_type='OrderClosed'));
""") == "2", 85, "durable paid and closed order messages published")
        self.stop(self.apps["trade"])
        self.service("fulfillment", {"ecommerce.fulfillment.geo.cache-enabled": "false",
                                     "ecommerce.fulfillment.reconciliation.enabled": "false",
                                     "ecommerce.fulfillment.after-sale-consumer.enabled": "false"})
        self.host_check("host-fulfillment-consumer.json")
        self.wait_for(lambda: self.shipment_row(paid) is not None, 85, "Fulfillment consumed real OrderPaid")
        paid_row = self.shipment_row(paid)
        self.save("paid-fulfillment-created.json", paid_row)
        assert paid_row["status"] == "CREATED" and paid_row["orderPaidReceipts"] == 1
        absent = self.get("fulfillment", "/api/v1/fulfillment/orders/" + exception, headers=self.customer)
        self.save("refunded-no-fulfillment.json", absent)
        assert absent["status"] == 404 and absent["body"]["code"] == "RESOURCE_NOT_FOUND"
        assert self.shipment_row(exception) is None
        for cid in (self.proxy, self.broker, self.namesrv):
            self.stop(cid)

        self.stage = "shipping-versus-before-completion-after-sale"
        self.trade("trade-shipping")
        self.host_check("host-shipment-vs-return.json")
        admin = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}
        path = "/api/v1/fulfillment/admin/orders/" + paid_row["id"]
        for action, expected in (("picking", "PICKING"), ("packed", "PACKED")):
            result = self.data("fulfillment-" + action, self.get("fulfillment", path + "/" + action, {}, admin))
            assert result["status"] == expected
        self.no_after_sale("sale-before-ship-rejected", self.apply_after_sale(paid, "P0-12-BEFORE-SHIP"))
        body = {"carrier": "MOCK", "trackingNo": "P0-12-TRACKING"}
        def timed(call):
            started = time.time_ns()
            response = call()
            return {"started_ns": started, "ended_ns": time.time_ns(), "result": response}
        with ThreadPoolExecutor(2) as executor:
            shipping = executor.submit(timed, lambda: self.get("fulfillment", path + "/ship", body, admin))
            aftersale = executor.submit(timed, lambda: self.apply_after_sale(paid, "P0-12-WHILE-SHIP"))
            pair = {"shipment": shipping.result(), "afterSale": aftersale.result()}
        self.save("shipment-concurrent-after-sale.json", pair)
        assert max(pair["shipment"]["started_ns"], pair["afterSale"]["started_ns"]) < \
               min(pair["shipment"]["ended_ns"], pair["afterSale"]["ended_ns"])
        assert self.data("dispatch-success", pair["shipment"]["result"])["status"] == "SHIPPED"
        self.no_after_sale("sale-while-ship-rejected", pair["afterSale"]["result"])
        duplicate = self.data("repeat-dispatch", self.get("fulfillment", path + "/ship", body, admin))
        assert duplicate["status"] == "SHIPPED" and duplicate["trackingNo"] == body["trackingNo"]
        self.stop(self.apps["trade"])

        self.stage = "trade-receives-shipment"
        self.restart_broker()
        self.host_check("host-shipment-publish.json")
        self.wait_for(lambda: self.shipment_row(paid)["shipEventStatus"] == "PUBLISHED",
                      90, "ShipmentDispatched reached RocketMQ")
        self.stop(self.apps["fulfillment"])
        self.trade("trade-logistics", **{"ecommerce.trade.fulfillment-consumer.enabled": "true"})
        self.host_check("host-logistics-consumer.json")
        self.wait_for(lambda: self.states(paid)["orderState"] == "SHIPPED", 90, "Trade projects real shipment")
        self.no_after_sale("sale-after-ship-rejected", self.apply_after_sale(paid, "P0-12-AFTER-SHIP"))

        self.stage = "read-four-ledgers"
        control = self.refund_state(paid) | {"fulfillment": self.shipment_row(paid)}
        target = self.refund_state(exception) | {"fulfillment": self.shipment_row(exception)}
        self.save("final-paid-shipment-four-ledgers.json", control)
        self.save("final-exception-no-shipment-four-ledgers.json", target)
        assert control["orderState"] == control["fulfillment"]["status"] == "SHIPPED"
        assert control["fulfillment"]["tracking"] == body["trackingNo"]
        assert control["fulfillment"]["orderPaidReceipts"] == control["fulfillment"]["shipEvent"] == \
               control["fulfillment"]["shipReceipts"] == 1
        assert control["payment"]["transactions"] == 1 and control["payment"]["successfulAmount"] == 39.8
        assert control["reservation"] == "CONFIRMED" and control["stock"] == 9 and control["refund"] is None
        assert target["fulfillment"] is None and target["orderState"] == "CLOSED"
        assert target["payment"]["successfulAmount"] == target["refund"]["amountRefunded"] == 39.8
        assert target["stock"] == 10 and target["reservation"] == "RELEASED" and target["orderPaid"] == 0
        assert self.sql("SELECT COUNT(*) FROM b1_trade.after_sale_order;") == "0"
        self.result.update(status="PASS_SCOPED", cases=self.result["cases"] + ["P0-12-shipment-and-refund-boundary"],
                           scope="isolated real services, refund-first on exceptional lane and ordinary shipment/after-sale interleaving")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = ShipmentCases(parser.parse_args())
    try:
        run.execute()
    except Exception as exc:
        run.result.update(stage=run.stage, error=repr(exc))
    finally:
        run.cleanup()
        run.save("result.json", run.result)
        run.save("sha256.json", {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in run.directory.iterdir() if path.is_file() and path.name != "sha256.json"})
    print(json.dumps(run.result, ensure_ascii=False))
    return 0 if run.result["status"] == "PASS_SCOPED" and run.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
