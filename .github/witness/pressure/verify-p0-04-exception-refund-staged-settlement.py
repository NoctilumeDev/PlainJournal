import argparse
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
spec = importlib.util.spec_from_file_location("cancel_cases", HERE / "verify-p0-03-cancel-staged-settlement.py")
cancel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cancel)
base = cancel.base


class RefundCase(cancel.CancelCases):
    def container(self, suffix, image, options, command):
        if suffix in {"payment-refund", "trade-refund"}:
            command = ["-Xmx224m" if arg == "-Xmx288m" else arg for arg in command]
        return super().container(suffix, image, options, command)

    def refund_state(self, order):
        base_state = self.states(order)
        state = self.sql(f"""
SELECT JSON_OBJECT(
 'no',r.refund_no,'originalPaymentNo',r.payment_no,'order',r.order_no,
 'reasonReference',r.after_sale_no,'amount',r.amount,'status',r.status,
 'requestStatus',r.request_status,'requestAttempts',r.request_attempts,
 'channelRefundNo',r.channel_refund_no,'tradeRefundNo',o.exception_refund_no,
 'audits',(SELECT COUNT(*) FROM b1_payment.payment_exception_refund_audit WHERE refund_no=r.refund_no),
 'callbackCount',(SELECT COUNT(*) FROM b1_payment.refund_callback_log WHERE refund_no=r.refund_no),
 'transactions',(SELECT COUNT(*) FROM b1_payment.refund_transaction WHERE refund_id=r.id AND status='SUCCESS'),
 'amountRefunded',COALESCE((SELECT SUM(amount) FROM b1_payment.refund_transaction
                            WHERE refund_id=r.id AND status='SUCCESS'),0),
 'refundEventCount',(SELECT COUNT(*) FROM b1_payment.outbox_event WHERE aggregate_id=r.refund_no
                       AND event_type='RefundSucceeded'),
 'refundEventStatus',(SELECT status FROM b1_payment.outbox_event WHERE aggregate_id=r.refund_no
                         AND event_type='RefundSucceeded'),
 'refundReceipts',(SELECT COUNT(*) FROM b1_trade.consumed_event c
                       JOIN b1_payment.outbox_event p ON c.event_id=p.id
                       WHERE p.aggregate_id=r.refund_no AND p.event_type='RefundSucceeded'),
 'closedEvents',(SELECT COUNT(*) FROM b1_trade.outbox_event
                    WHERE aggregate_id=o.order_no AND event_type='OrderClosed')
)
FROM b1_payment.refund_order r
JOIN b1_trade.trade_order o ON o.order_no=r.order_no
WHERE r.order_no='{order}';
""")
        return base_state | {"refund": json.loads(state) if state else None}

    def restart_broker(self):
        for cid in (self.namesrv, self.broker):
            self.docker("start", cid, timeout=35)
        self.wait_for(lambda: "EcommerceCluster" in self.admin("clusterList", check=False),
                      80, "same isolated broker registration after restart")
        self.docker("start", self.proxy, timeout=35)
        self.wait_for(lambda: any(":" + f"{18082:04X}" in line and line.split()[3] == "0A"
                                   for line in self.docker("exec", self.broker, "cat", "/proc/net/tcp",
                                                           "/proc/net/tcp6").splitlines()),
                      65, "same isolated proxy after restart")
        self.save("refund-broker-readback.json",
                  {"cluster": self.admin("clusterList"), "topic": self.admin("topicStatus", "-t",
                                                                             "ecommerce-payment-events")})

    def execute(self):
        super().execute()
        self.result.update(status="INCONCLUSIVE", prior_cancel_cases=self.result["cases"], cases=[])
        self.save("refund-test-identity.json",
                  {"runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   "prerequisite_runner_sha256": hashlib.sha256(Path(cancel.__file__).read_bytes()).hexdigest()})
        paid, exception = cancel.ORDERS
        existing_trade = self.apps["trade"]
        original_payment = self.refund_state(exception)["linkedPayment"]
        healthy_payment = self.refund_state(paid)["linkedPayment"]
        assert original_payment and healthy_payment and original_payment != healthy_payment
        self.stop(self.apps["inventory"])
        self.stage = "exception-refund-request"
        self.service("payment", {"ecommerce.payment.client.trade-base-url": "http://trade:18104",
                                 "ecommerce.payment.client.service-discovery-enabled": "false",
                                 "ecommerce.payment.refund-dispatch.enabled": "true",
                                 "ecommerce.payment.refund-consumer.enabled": "false",
                                 "ecommerce.payment.reconciliation.enabled": "false"}, "payment-refund")
        self.host_check("host-refund-request.json")
        payment = self.apps["payment"]
        admin = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}
        def ask(pay_no, operation):
            return self.get("payment", f"/api/v1/payment/admin/payments/{pay_no}/exception-refunds",
                            {"reason": "Isolated refund acceptance case"}, {**admin, "Idempotency-Key": operation})
        control = ask(healthy_payment, "P0-04-CONTROL")
        self.save("ordinary-payment-exception-refund-rejected.json", control)
        assert control["status"] == 409 and control["body"]["code"] == "PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED"
        requested = self.data("exception-refund-request", ask(original_payment, "P0-04-EXCEPTION"))
        replay = self.data("exception-refund-request-replay", ask(original_payment, "P0-04-EXCEPTION"))
        refund_no = requested["refundNo"]
        assert replay["refundNo"] == refund_no and requested["amount"] == 39.8
        self.wait_for(lambda: self.refund_state(exception)["refund"]["requestStatus"] == "SENT",
                      35, "simulated channel request acknowledged")
        awaiting = self.refund_state(exception)
        self.save("refund-sent-not-settled.json", awaiting)
        assert awaiting["payment"]["refunds"] == 1 and awaiting["refund"]["audits"] == 1
        assert awaiting["refund"]["status"] == "PROCESSING" and awaiting["refund"]["transactions"] == 0
        assert awaiting["refund"]["originalPaymentNo"] == original_payment
        self.stop(existing_trade)
        self.stage = "refund-callback-and-publish"
        self.restart_broker()
        self.host_check("host-refund-publish.json")
        stamp = int(time.time())
        event = "P0-04-MOCK-REFUND-RESULT"
        external = "P0-04-MOCK-REFUND-TXN"
        message = "|".join((refund_no, event, external, "SUCCESS", "39.8", str(stamp)))
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        payload = {"refundNo": refund_no, "externalEventId": event, "externalRefundNo": external,
                   "status": "SUCCESS", "amount": 39.8, "timestamp": stamp, "signature": signature}
        for index in range(2):
            updated = self.data(f"refund-callback-{index}", self.get("payment", "/api/v1/payment/callbacks/mock/refunds", payload))
            assert updated["refundNo"] == refund_no and updated["status"] == "SUCCESS"
        self.wait_for(lambda: self.refund_state(exception)["refund"]["refundEventStatus"] == "PUBLISHED",
                      80, "refund result physically published")
        settled = self.refund_state(exception)
        self.save("refund-settled-before-trade.json", settled)
        assert settled["orderState"] == "PAYMENT_EXCEPTION" and settled["refund"]["refundReceipts"] == 0
        assert settled["refund"]["amountRefunded"] == settled["refund"]["amount"] == 39.8
        assert settled["refund"]["transactions"] == settled["refund"]["callbackCount"] == settled["refund"]["refundEventCount"] == 1
        self.stop(payment)

        self.stage = "trade-receives-refund"
        self.service("trade", {**{f"ecommerce.trade.{feature}.enabled": "false" for feature in (
            "outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
            "after-sale-fulfillment-consumer", "after-sale-inventory-consumer", "reconciliation")},
            "ecommerce.trade.refund-result-consumer.enabled": "true",
            "ecommerce.trade.client.service-discovery-enabled": "false",
            "ecommerce.trade.order.recovery-enabled": "false"}, "trade-refund")
        self.host_check("host-trade-refund.json")
        self.wait_for(lambda: self.refund_state(exception)["orderState"] == "CLOSED", 90,
                      "Trade closes the exceptional order after consuming refund")
        closed = self.refund_state(exception)
        self.save("closed-before-repay.json", closed)
        assert closed["refund"]["refundReceipts"] == closed["refund"]["closedEvents"] == 1
        assert closed["refund"]["tradeRefundNo"] == refund_no
        assert tuple(closed[field] for field in ("stock", "reserved", "reservation", "confirmations", "orderPaid")) \
               == (10, 0, "RELEASED", 0, 0)
        for cid in (self.proxy, self.broker, self.namesrv):
            self.stop(cid)

        self.stage = "repay-same-closed-order"
        self.docker("start", payment)
        self.ready("payment")
        self.host_check("host-retry-refunded-order.json")
        for label, key in (("same", "IDEM-" + exception), ("new", "P0-04-NEW-PAYMENT-KEY")):
            answer = self.get("payment", cancel.p0.PAYMENT_PATH, {"orderNo": exception, "channel": "MOCK"},
                              self.headers(key))
            self.save("repay-" + label + "-key.json", answer)
            if answer["status"] == 200:
                assert answer["body"]["data"]["paymentNo"] == original_payment
            else:
                assert answer["status"] == 409 and answer["body"]["code"] == "INVALID_STATE"
        final = self.refund_state(exception)
        self.save("final-exception-refund-ledgers.json", final)
        self.save("final-healthy-control-ledgers.json", self.refund_state(paid))
        assert final == closed and final["payment"]["transactions"] == final["payment"]["payments"] == 1
        assert final["payment"]["successfulAmount"] == final["refund"]["amountRefunded"] == 39.8
        view = self.data("final-customer-order", self.order_state(exception))
        assert view["status"] == "CLOSED"
        refund_view = self.data("final-customer-refund", self.get("payment", "/api/v1/payment/refunds/" + refund_no,
                                                                 headers=self.customer))
        assert refund_view["refundNo"] == refund_no and refund_view["status"] == "SUCCESS"
        self.result.update(status="PASS_SCOPED", cases=["P0-04-exception-refund-repay"],
                           scope="real sandbox cancellation, payment, refund, MySQL, RocketMQ and stock; no routine returned goods or shipment")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = RefundCase(parser.parse_args())
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
