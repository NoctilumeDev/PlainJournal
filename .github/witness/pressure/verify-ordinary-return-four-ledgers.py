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
spec = importlib.util.spec_from_file_location("shipment_cases", HERE / "verify-p0-11-12-shipment-staged-settlement.py")
shipment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shipment)
base = shipment.base


class RegularReturn(shipment.ShipmentCases):
    def container(self, suffix, image, options, command):
        if suffix in {"trade-returns", "fulfillment-return", "inventory-return", "payment-return"}:
            command = ["-Xmx224m" if part == "-Xmx288m" else part for part in command]
        return super().container(suffix, image, options, command)

    def published(self, database, aggregate, event):
        return self.sql(f"""
SELECT COUNT(*) FROM {database}.outbox_event
WHERE aggregate_id='{aggregate}' AND event_type='{event}' AND status='PUBLISHED';
""") == "1"

    def return_terminal_facts(self, order, after_sale):
        stock_id = shipment.refund.cancel.SKU_BY_ORDER[order]
        return json.loads(self.sql(f"""
SELECT JSON_OBJECT(
  'order',s.order_no, 'afterSale',s.after_sale_no, 'state',s.status,
  'refundNoInTrade',s.refund_no, 'quoted',o.total_amount, 'paidNo',o.payment_no,
  'returnReceiptNo',rr.return_receipt_no, 'returnState',rr.status, 'returnTracking',rr.tracking_no,
  'sameReceiptAtInventory',(ir.return_receipt_no=rr.return_receipt_no),
  'sameOrderAtPayment',(r.order_no=o.order_no), 'sameOriginalPayment',(r.payment_no=o.payment_no),
  'sameRefundNo',(r.refund_no=s.refund_no), 'stock',(SELECT on_hand FROM b1_inventory.inventory_balance WHERE sku_id={stock_id}),
  'stocked',(SELECT COUNT(*) FROM b1_inventory.inventory_return WHERE after_sale_no=s.after_sale_no AND status='STOCKED'),
  'returnedShipmentCount',(SELECT COUNT(*) FROM b1_fulfillment.return_receipt WHERE after_sale_no=s.after_sale_no),
  'inventoryReceiptCount',(SELECT COUNT(*) FROM b1_inventory.consumed_event a JOIN b1_fulfillment.outbox_event b
      ON b.id=a.event_id WHERE b.aggregate_id=rr.return_receipt_no AND b.event_type='ReturnInspected'),
  'refundReceiptCount',(SELECT COUNT(*) FROM b1_trade.consumed_event a JOIN b1_payment.outbox_event b
      ON b.id=a.event_id WHERE b.aggregate_id=r.refund_no AND b.event_type='RefundSucceeded')
)
FROM b1_trade.after_sale_order s JOIN b1_trade.trade_order o ON o.order_no=s.order_no
JOIN b1_payment.refund_order r ON r.after_sale_no=s.after_sale_no
JOIN b1_fulfillment.return_receipt rr ON rr.after_sale_no=s.after_sale_no
JOIN b1_inventory.inventory_return ir ON ir.after_sale_no=s.after_sale_no
WHERE s.after_sale_no='{after_sale}';
"""))

    def execute(self):
        super().execute()
        self.result.update(status="INCONCLUSIVE", prerequisite_shipment_cases=self.result["cases"], cases=[])
        self.save("regular-return-test-identity.json", {"runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "shipment_runner_sha256": hashlib.sha256(Path(shipment.__file__).read_bytes()).hexdigest()})
        paid, exception = shipment.refund.cancel.ORDERS
        shipping = self.shipment_row(paid)
        assert shipping["status"] == "SHIPPED"
        admin = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}
        self.stage = "regular-customer-confirm-receipt"
        self.stop(self.apps["trade"])
        self.docker("start", self.apps["fulfillment"])
        self.ready("fulfillment")
        self.host_check("host-confirm-receipt.json")
        receipt_url = "/api/v1/fulfillment/orders/" + paid + "/confirm-receipt"
        receipt = self.data("ordinary-customer-confirm-receipt", self.get("fulfillment", receipt_url, {}, self.customer))
        assert receipt["status"] == "SIGNED"
        self.wait_for(lambda: self.published("b1_fulfillment", shipping["id"], "ShipmentSigned"), 85, "signed event published")
        self.stop(self.apps["fulfillment"])

        self.stage = "regular-after-sale-application-review"
        self.trade("trade-returns", **{
            "ecommerce.trade.outbox.enabled": "true", "ecommerce.trade.fulfillment-consumer.enabled": "true",
            "ecommerce.trade.after-sale-fulfillment-consumer.enabled": "true",
            "ecommerce.trade.after-sale-inventory-consumer.enabled": "true",
            "ecommerce.trade.refund-result-consumer.enabled": "true"})
        trade = self.apps["trade"]
        self.host_check("host-prepare-ordinary-after-sale.json")
        self.wait_for(lambda: self.states(paid)["orderState"] == "COMPLETED", 90, "Trade projects signed order")
        applied = self.data("ordinary-after-sale-application", self.apply_after_sale(paid, "P1-REGULAR-RETURN"))
        assert applied["status"] == "APPLIED"
        after_sale = applied["afterSaleNo"]
        body = {"approved": True, "reason": "Synthetic ordinary return accepted"}
        for n in range(2):
            approved = self.data(f"ordinary-after-sale-approve-{n}", self.get("trade",
                f"/api/v1/trade/admin/after-sales/{after_sale}/review", body, admin))
            assert approved["status"] == "WAIT_RETURN" and approved["afterSaleNo"] == after_sale
        self.wait_for(lambda: self.published("b1_trade", after_sale, "AfterSaleApproved"),
                      85, "regular return approval published")
        self.stop(trade)

        self.stage = "regular-return-transport-and-inspection"
        self.service("fulfillment", {"ecommerce.fulfillment.geo.cache-enabled": "false",
                                     "ecommerce.fulfillment.reconciliation.enabled": "false",
                                     "ecommerce.fulfillment.order-consumer.enabled": "false",
                                     "ecommerce.fulfillment.after-sale-consumer.enabled": "true"}, "fulfillment-return")
        self.host_check("host-warehouse-return.json")
        lookup = f"SELECT return_receipt_no FROM b1_fulfillment.return_receipt WHERE after_sale_no='{after_sale}'"
        self.wait_for(lambda: bool(self.sql(lookup)), 85, "warehousing receives approved return")
        return_no = self.sql(lookup)
        customer_url = "/api/v1/fulfillment/returns/" + return_no
        before = self.data("ordinary-return-available", self.get("fulfillment", customer_url, headers=self.customer))
        assert before["status"] == "WAIT_SHIPMENT"
        sent = self.data("ordinary-return-shipment", self.get("fulfillment", customer_url + "/shipment",
                   {"carrier": "MOCK", "trackingNo": "P0-REGULAR-RETURN-TRACK-01"}, self.customer))
        assert sent["status"] == "RETURNING"
        receipt_url = "/api/v1/fulfillment/admin/returns/" + return_no
        received = self.data("ordinary-return-warehouse-receipt", self.get("fulfillment", receipt_url + "/receive", {}, admin))
        assert received["status"] == "RECEIVED"
        for n in range(2):
            inspected = self.data(f"ordinary-return-inspection-{n}", self.get("fulfillment", receipt_url + "/inspect",
                {"remark": "One synthetic unit inspected successfully"}, admin))
            assert inspected["status"] == "INSPECTED"
        self.wait_for(lambda: self.published("b1_fulfillment", return_no, "ReturnInspected"), 85,
                      "return inspection published")
        self.stop(self.apps["fulfillment"])

        self.stage = "inventory-receives-and-restocks-ordinary-return"
        topic = self.admin("updateTopic", "-c", "EcommerceCluster", "-t", "ecommerce-inventory-events", "-r", "4", "-w", "4")
        assert "success" in topic.lower()
        self.service("inventory", {"ecommerce.inventory.order-consumer.enabled": "false",
                                    "ecommerce.inventory.return-consumer.enabled": "true",
                                    "ecommerce.inventory.reconciliation.enabled": "false",
                                    "ecommerce.inventory.outbox.enabled": "true"}, "inventory-return")
        self.host_check("host-inventory-return.json")
        self.wait_for(lambda: self.published("b1_inventory", after_sale, "ReturnStocked"), 85,
                      "actual Inventory stock on return")
        assert self.states(paid)["stock"] == 10
        self.stop(self.apps["inventory"])

        self.stage = "trade-receives-restock-before-refund"
        self.docker("start", trade)
        self.ready("trade")
        self.host_check("host-return-trade-outbox.json")
        self.wait_for(lambda: self.published("b1_trade", after_sale, "RefundRequested"), 90,
                      "Trade requests regular refund following ReturnStocked")
        self.stop(trade)
        self.stage = "payment-requests-and-settles-original-refund"
        self.service("payment", {"ecommerce.payment.refund-consumer.enabled": "true",
                                 "ecommerce.payment.refund-dispatch.enabled": "true",
                                 "ecommerce.payment.reconciliation.enabled": "false",
                                 "ecommerce.payment.client.trade-base-url": "http://trade:18104",
                                 "ecommerce.payment.client.service-discovery-enabled": "false"}, "payment-return")
        self.host_check("host-ordinary-payment-refund.json")
        self.wait_for(lambda: (self.refund_state(paid)["refund"] or {}).get("requestStatus") == "SENT",
                      95, "regular refund dispatched once")
        pending = self.refund_state(paid)
        self.save("ordinary-return-before-refund-callback.json", pending)
        assert pending["refund"]["status"] == "PROCESSING" and pending["refund"]["transactions"] == 0
        original = pending["linkedPayment"]
        refund_no = pending["refund"]["no"]
        assert original == pending["refund"]["originalPaymentNo"] and pending["refund"]["amount"] == 39.8
        stamp = int(time.time())
        event, external = "P0-RETURN-MOCK-RESULT", "P0-RETURN-MOCK-TXN"
        message = "|".join((refund_no, event, external, "SUCCESS", "39.8", str(stamp)))
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        payload = {"refundNo": refund_no, "externalEventId": event, "externalRefundNo": external,
                   "status": "SUCCESS", "amount": 39.8, "timestamp": stamp, "signature": signature}
        for n in range(2):
            callback = self.data(f"ordinary-return-refund-callback-{n}",
                                 self.get("payment", "/api/v1/payment/callbacks/mock/refunds", payload))
            assert callback["status"] == "SUCCESS" and callback["refundNo"] == refund_no
        self.wait_for(lambda: self.published("b1_payment", refund_no, "RefundSucceeded"),
                      85, "ordinary mock refund success published")
        self.stop(self.apps["payment"])

        self.stage = "ordinary-return-final-four-ledgers"
        self.docker("start", trade)
        self.ready("trade")
        self.host_check("host-ordinary-final.json")
        def is_completed():
            return self.sql(f"SELECT status FROM b1_trade.after_sale_order WHERE after_sale_no='{after_sale}';") == "COMPLETED"
        self.wait_for(is_completed, 90, "Trade finalizes ordinary after-sale after refund")
        end = self.refund_state(paid)
        facts = self.return_terminal_facts(paid, after_sale)
        outbound = self.shipment_row(paid)
        self.save("ordinary-return-four-ledgers.json", {"tradePaymentRefundStock": end, "outbound": outbound,
                                                       "inbound": facts, "exception": self.refund_state(exception)})
        assert end["orderState"] == "COMPLETED" and outbound["status"] == "SIGNED" and outbound["shipEvent"] == 1
        assert end["payment"]["payments"] == end["payment"]["transactions"] == end["refund"]["transactions"] == 1
        assert end["payment"]["successfulAmount"] == end["refund"]["amountRefunded"] == facts["quoted"] == 39.8
        assert end["refund"]["callbackCount"] == end["refund"]["refundEventCount"] == 1
        assert facts["returnState"] == "INSPECTED" and facts["returnReceiptNo"] == return_no
        assert facts["sameOriginalPayment"] and facts["sameRefundNo"] and facts["sameReceiptAtInventory"]
        assert facts["stock"] == 10 and facts["returnedShipmentCount"] == facts["stocked"] == facts["refundReceiptCount"] == 1
        self.result.update(status="PASS_SCOPED", cases=["ordinary-fulfilled-return-and-refund-four-ledgers"],
                           scope="actual candidate applications, MySQL, local RocketMQ, synthetic payment and transport")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = RegularReturn(parser.parse_args())
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
