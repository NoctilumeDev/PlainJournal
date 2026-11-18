import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location("payment_cases", HERE / "verify-p0-payment-recovery.py")
p0 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p0)
base = p0.base
ORDERS = ("BOOT412-P0-03-PAY", "BOOT412-P0-03-CANCEL")
SKU_BY_ORDER = {order: 842001 + i for i, order in enumerate(ORDERS)}
WAREHOUSE = 842100


class CancelCases(p0.PaymentCases):
    def container(self, suffix, image, options, command):
        if suffix in {"payment", "trade-prep", "trade-consumer", "inventory"}:
            command = ["-Xmx224m" if arg == "-Xmx288m" else arg for arg in command]
        return super().container(suffix, image, options, command)


    def order_state(self, order):
        path = "/api/v1/trade/orders/" + order
        return self.get("trade", path, headers=self.customer)

    def cancel(self, order):
        return self.get("trade", "/api/v1/trade/orders/" + order + "/cancel", {}, self.customer)

    def states(self, order):
        sku = SKU_BY_ORDER[order]
        sql = f"""
SELECT JSON_OBJECT(
  'orderState',(SELECT status FROM b1_trade.trade_order WHERE order_no='{order}'),
  'linkedPayment',(SELECT payment_no FROM b1_trade.trade_order WHERE order_no='{order}'),
  'reservation',(SELECT status FROM b1_inventory.inventory_reservation WHERE reservation_no='RSV-{order}'),
  'stock',(SELECT on_hand FROM b1_inventory.inventory_balance WHERE sku_id={sku}),
  'reserved',(SELECT reserved FROM b1_inventory.inventory_balance WHERE sku_id={sku}),
  'confirmations',(SELECT COUNT(*) FROM b1_inventory.stock_movement WHERE reservation_no='RSV-{order}'
                   AND movement_type='CONFIRM'),
  'orderPaid',(SELECT COUNT(*) FROM b1_trade.outbox_event WHERE aggregate_id='{order}' AND event_type='OrderPaid'),
  'paymentReview',(SELECT COUNT(*) FROM b1_trade.outbox_event
                   WHERE aggregate_id='{order}' AND event_type='PaymentReviewRequired'),
  'paymentEventStatus',(SELECT e.status FROM b1_payment.outbox_event AS e
                        JOIN b1_payment.payment_order AS p ON p.payment_no=e.aggregate_id
                        WHERE p.order_no='{order}' AND e.event_type='PaymentSucceeded'),
  'paymentReceipts',(SELECT COUNT(*) FROM b1_trade.consumed_event AS c
        JOIN b1_payment.outbox_event AS e ON c.event_id=e.id
        JOIN b1_payment.payment_order AS p ON p.payment_no=e.aggregate_id
        WHERE p.order_no='{order}' AND e.event_type='PaymentSucceeded')
);
"""
        return json.loads(self.sql(sql)) | {"payment": super().facts(order)}

    def execute(self):
        self.stage = "fixed-coordinates"
        candidate = json.loads((INPUTS / "coordinates.json").read_text(encoding="utf-8-sig"))
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        assert sha == candidate["candidate"] and not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.jars = json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql_image = self.image(os.environ["PJ_MYSQL_REF"])
        rmq_image = self.image(os.environ["PJ_ROCKETMQ_REF"])
        node_image = self.image(os.environ["PJ_NODE_REF"])
        self.save("coordinates.json", {
            "source": sha, "images": [self.java, mysql_image, rmq_image, node_image], "jars": self.jars,
            "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        })
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.stage = "owned-mysql"
        self.mysql = self.container("mysql", mysql_image, [
            "--network-alias", "mysql", "--memory", "760m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=600m", "-e", "MYSQL_ROOT_PASSWORD=" + base.runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC"
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.save("mysql-version.json", {"version": self.sql("SELECT VERSION()")})
        self.sql("CREATE DATABASE b1_inventory; CREATE DATABASE b1_trade; CREATE DATABASE b1_payment;")
        self.http = self.container("http", node_image, ["--memory", "96m", "--cpus", "1"], ["sleep", "3600"])
        self.customer = {"Authorization": "Bearer " + base.runtime.token("CUSTOMER")}
        inventory_overrides = {f"ecommerce.inventory.{feature}.enabled": "false"
                               for feature in ("outbox", "order-consumer", "return-consumer", "reconciliation")}
        trade_overrides = {f"ecommerce.trade.{feature}.enabled": "false" for feature in
                           ("outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
                            "after-sale-fulfillment-consumer", "after-sale-inventory-consumer",
                            "refund-result-consumer", "reconciliation")}
        trade_overrides.update({"ecommerce.trade.client.service-discovery-enabled": "false",
                                "ecommerce.trade.client.inventory-base-url": "http://inventory:18103",
                                "ecommerce.trade.order.recovery-enabled": "false"})
        payment_overrides = {f"ecommerce.payment.{feature}.enabled": "false"
                             for feature in ("refund-consumer", "refund-dispatch", "reconciliation")}
        payment_overrides.update({"ecommerce.payment.client.service-discovery-enabled": "false",
                                  "ecommerce.payment.client.trade-base-url": "http://trade:18104"})

        self.stage = "real-stock-and-order-fixtures"
        self.service("inventory", inventory_overrides)
        self.sql(f"""
INSERT INTO b1_inventory.warehouse(id,code,name,status,version,created_at,updated_at)
VALUES ({WAREHOUSE},'P0RACE','Isolated fixture','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
        for i, order in enumerate(ORDERS, 1):
            sku = SKU_BY_ORDER[order]
            self.sql(f"""
INSERT INTO b1_inventory.inventory_balance(id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES ({i},{WAREHOUSE},{sku},10,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
            expires = (datetime.now(timezone.utc) + timedelta(minutes=25)).isoformat()
            reserved = self.data(order + "-reserved", self.get("inventory", "/api/v1/inventory/internal/reservations",
                {"reservationNo": "RSV-" + order, "orderNo": order, "warehouseId": WAREHOUSE,
                 "expiresAt": expires, "items": [{"skuId": sku, "quantity": 1}]}, base.TRADE_HEADER))
            assert reserved["status"] == "RESERVED"
        stock = self.apps["inventory"]
        self.stop(stock)

        self.service("trade", trade_overrides, "trade-prep")
        initial_trade = self.apps["trade"]
        for i, order in enumerate(ORDERS, 1):
            sku = SKU_BY_ORDER[order]
            self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES ({i},'{order}',91001,'{order}',REPEAT('0',64),'RSV-{order}','P0RACE',{WAREHOUSE},
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 15 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_item
(id,order_id,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,payable_amount,created_at)
VALUES ({i},{i},841003,{sku},'Fixture product','P0RACE','Fixture SKU','{{}}',39.80,1,1,39.80,39.80,UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_address_snapshot
(id,order_id,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,created_at)
VALUES ({i},{i},841004,'Fixture Receiver','00000000000','Test province','Test city','Test district',
 'Fixture street','000000',UTC_TIMESTAMP(3));
""")
            order_view = self.data(order + "-precheck", self.order_state(order))
            assert order_view["status"] == "PENDING_PAYMENT" and order_view["deliveryAddress"]["recipientName"] == "Fixture Receiver"

        self.stage = "ordinary-payment-intents"
        self.service("payment", payment_overrides)
        payment = self.apps["payment"]
        self.host_check("host-create-intents.json")
        payment_ids = {}
        for order in ORDERS:
            intent = self.create(order, "IDEM-" + order, order + "-intent")
            assert intent["status"] == "PROCESSING" and intent["amount"] == 39.8
            payment_ids[order] = intent["paymentNo"]
        self.stop(payment)
        self.stage = "cancel-before-channel-settlement"
        self.docker("start", stock)
        self.ready("inventory")
        self.host_check("host-cancel-first.json")
        paid_first, canceled_first = ORDERS
        response = self.data(canceled_first + "-canceled-before-callback", self.cancel(canceled_first))
        assert response["status"] == "CANCELED"
        before = self.states(canceled_first)
        self.save(canceled_first + "-before-callback-ledgers.json", before)
        assert (before["reservation"], before["stock"], before["reserved"], before["confirmations"]) == ("RELEASED", 10, 0, 0)
        assert before["payment"]["paymentState"] == "PROCESSING" and before["payment"]["transactions"] == 0
        self.stop(stock)
        self.stop(initial_trade)
        self.stage = "mock-settlement-real-rocketmq"
        self.start_broker(rmq_image)
        self.docker("start", payment)
        self.ready("payment")
        self.host_check("host-settlement.json")
        for order in ORDERS:
            self.callback(order, payment_ids[order], 1)
        self.wait_for(lambda: all(self.states(order)["paymentEventStatus"] == "PUBLISHED" for order in ORDERS),
                      90, "both durable payment events published through RocketMQ")
        self.stop(payment)
        self.stage = "trade-applies-settlement"
        self.service("trade", {**trade_overrides, "ecommerce.trade.payment-consumer.enabled": "true",
                               "ecommerce.trade.order.recovery-enabled": "true"}, "trade-consumer")
        self.host_check("host-trade-consumer.json")
        self.wait_for(lambda: self.states(canceled_first)["orderState"] == "PAYMENT_EXCEPTION"
                      and self.states(paid_first)["orderState"] == "PAYMENT_CONFIRMING",
                      90, "ordinary and late settlement diverge inside Trade")
        for order in ORDERS:
            self.save(order + "-trade-consumed.json", self.states(order))
        for cid in (self.proxy, self.broker, self.namesrv):
            self.stop(cid)
        self.stage = "authoritative-stock-and-final-cancellation"
        self.docker("start", stock)
        self.ready("inventory")
        self.host_check("host-stock-confirmation.json")
        self.wait_for(lambda: self.states(paid_first)["orderState"] == "PAID", 100, "inventory confirmation completes order")
        after = self.cancel(paid_first)
        self.save(paid_first + "-cancel-after-payment.json", after)
        assert after["status"] == 409 and after["body"]["code"] == "INVALID_STATE"
        expected = {
            paid_first: ("PAID", "CONFIRMED", 9, 0, 1, 1, 0),
            canceled_first: ("PAYMENT_EXCEPTION", "RELEASED", 10, 0, 0, 0, 1)
        }
        for order in ORDERS:
            final = self.states(order)
            self.save(order + "-final-ledgers.json", final)
            view = self.data(order + "-final-api", self.order_state(order))
            assert view["status"] == expected[order][0]
            fields = ("orderState", "reservation", "stock", "reserved", "confirmations", "orderPaid", "paymentReview")
            assert tuple(final[field] for field in fields) == expected[order]
            facts = final["payment"]
            assert facts["payments"] == facts["transactions"] == facts["callbacks"] == facts["successEvents"] == final["paymentReceipts"] == 1
            assert facts["refunds"] == 0 and facts["successfulAmount"] == facts["amount"] == 39.8
            assert final["linkedPayment"] == facts["paymentNo"] == payment_ids[order]
        self.host_check("host-final.json")
        self.result.update(status="PASS_SCOPED", source=sha, cases=["P0-03-cancel-first", "P0-03-payment-first"],
                           scope="separate ordered commits; actual RocketMQ, MySQL and Inventory; no physical shipment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = CancelCases(parser.parse_args())
    try:
        run.execute()
    except Exception as error:
        run.result.update(stage=run.stage, error=repr(error))
    finally:
        run.cleanup()
        run.save("result.json", run.result)
        run.save("sha256.json", {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                                for f in run.directory.iterdir() if f.is_file() and f.name != "sha256.json"})
    print(json.dumps(run.result, ensure_ascii=False))
    return 0 if run.result["status"] == "PASS_SCOPED" and run.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
