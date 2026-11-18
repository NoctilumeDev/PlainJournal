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
spec = importlib.util.spec_from_file_location("boot_runtime", HERE / "verify-boot412-payment-inventory.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

ORDERS = ("BOOT412-P0-01", "BOOT412-P0-02")
PAYMENT_PATH = "/api/v1/payment/payments"
DROP_RESPONSE = """
const { createServer } = require("node:http");
const fs = require("node:fs");
const fixture = JSON.parse(fs.readFileSync(0, "utf8"));
async function run() {
  let upstream;
  let handlerError;
  const listener = createServer(async (req, res) => {
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const reply = await fetch(fixture.target, {
        method: "POST", headers: fixture.headers, body: Buffer.concat(chunks),
        signal: AbortSignal.timeout(8000)
      });
      const body = await reply.json();
      upstream = { status: reply.status, code: body.code, paymentNo: body.data?.paymentNo, state: body.data?.status };
    } catch (error) {
      handlerError = String(error);
    } finally {
      res.destroy();
    }
  });
  await new Promise(resolve => listener.listen(0, "127.0.0.1", resolve));
  const address = "http://127.0.0.1:" + listener.address().port;
  let closed = false;
  try {
    await fetch(address, {
      method: "POST", body: JSON.stringify(fixture.body),
      headers: {"Content-Type": "application/json"}, signal: AbortSignal.timeout(9000)
    });
  } catch (error) {
    closed = error.cause?.code === "UND_ERR_SOCKET" || error.cause?.code === "ECONNRESET";
  } finally {
    listener.closeAllConnections();
    await new Promise(resolve => listener.close(resolve));
  }
  console.log(JSON.stringify({upstream, closed, handlerError}));
}
run().catch(error => { console.error(String(error)); process.exitCode = 1; });
"""


class PaymentCases(base.Chain):
    def facts(self, order):
        sql = f"""
SELECT JSON_OBJECT(
  'orderState',(SELECT status FROM b1_trade.trade_order WHERE order_no='{order}'),
  'amount',(SELECT amount FROM b1_payment.payment_order WHERE order_no='{order}'),
  'paymentNo',(SELECT payment_no FROM b1_payment.payment_order WHERE order_no='{order}'),
  'paymentState',(SELECT status FROM b1_payment.payment_order WHERE order_no='{order}'),
  'payments',(SELECT COUNT(*) FROM b1_payment.payment_order WHERE order_no='{order}'),
  'transactions',(SELECT COUNT(*) FROM b1_payment.payment_transaction AS tx
          JOIN b1_payment.payment_order AS p ON p.id=tx.payment_id WHERE p.order_no='{order}'),
  'successfulAmount',(SELECT SUM(tx.amount) FROM b1_payment.payment_transaction AS tx
          JOIN b1_payment.payment_order AS p ON p.id=tx.payment_id
          WHERE p.order_no='{order}' AND tx.status='SUCCESS'),
  'callbacks',(SELECT COUNT(*) FROM b1_payment.payment_callback_log AS c
          JOIN b1_payment.payment_order AS p ON p.payment_no=c.payment_no WHERE p.order_no='{order}'),
  'successEvents',(SELECT COUNT(*) FROM b1_payment.outbox_event AS e
          JOIN b1_payment.payment_order AS p ON p.payment_no=e.aggregate_id
          WHERE p.order_no='{order}' AND e.event_type='PaymentSucceeded'),
  'refunds',(SELECT COUNT(*) FROM b1_payment.refund_order WHERE order_no='{order}'));
"""
        return json.loads(self.sql(sql))

    def headers(self, key):
        return {**self.customer, "Idempotency-Key": key}

    def create(self, order, key, label):
        return self.data(label, self.get("payment", PAYMENT_PATH,
                         {"orderNo": order, "channel": "MOCK"}, self.headers(key)))

    def callback(self, order, payment_no, duplicates):
        stamp = int(time.time())
        event = "FIXTURE-EVT-" + order
        tx = "FIXTURE-TXN-" + order
        message = "|".join((payment_no, event, tx, "SUCCESS", "39.8", str(stamp)))
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        data = {"paymentNo": payment_no, "externalEventId": event, "externalTransactionNo": tx,
                "status": "SUCCESS", "amount": 39.8, "timestamp": stamp, "signature": signature}
        for i in range(duplicates):
            response = self.data(order + f"-callback-{i}", self.get("payment", "/api/v1/payment/callbacks/mock", data))
            assert response["status"] == "SUCCESS" and response["paymentNo"] == payment_no
        return tx

    def assert_settled(self, order, payment_no):
        values = self.facts(order)
        self.save(order + "-settled-ledgers.json", values)
        assert values["payments"] == values["transactions"] == values["callbacks"] == values["successEvents"] == 1
        assert values["orderState"] == "PENDING_PAYMENT" and values["paymentState"] == "SUCCESS"
        assert values["paymentNo"] == payment_no and values["successfulAmount"] == values["amount"] == 39.8
        assert values["refunds"] == 0

    def execute(self):
        self.stage = "fixed-coordinates"
        candidate = json.loads((INPUTS / "coordinates.json").read_text(encoding="utf-8-sig"))
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        assert sha == candidate["candidate"] and not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.jars = json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql_image = self.image(os.environ["PJ_MYSQL_REF"])
        node_image = self.image(os.environ["PJ_NODE_REF"])
        self.save("coordinates.json", {
            "source": sha, "images": [self.java, mysql_image, node_image],
            "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "base_sha256": hashlib.sha256(Path(base.__file__).read_bytes()).hexdigest()
        })
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.stage = "owned-mysql"
        self.mysql = self.container("mysql", mysql_image, [
            "--network-alias", "mysql", "--memory", "700m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=600m", "-e", "MYSQL_ROOT_PASSWORD=" + base.runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC"
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.save("mysql-version.json", {"version": self.sql("SELECT VERSION()")})
        self.sql("CREATE DATABASE b1_trade; CREATE DATABASE b1_payment;")
        self.http = self.container("http", node_image, ["--memory", "96m", "--cpus", "1"], ["sleep", "3600"])
        self.stage = "exact-trade-and-payment"
        self.service("trade", {**{
            f"ecommerce.trade.{part}.enabled": "false" for part in
            ("outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer", "after-sale-fulfillment-consumer",
             "after-sale-inventory-consumer", "refund-result-consumer", "reconciliation")
        }, "ecommerce.trade.client.service-discovery-enabled": "false", "ecommerce.trade.order.recovery-enabled": "false"})
        for i, order in enumerate(ORDERS, 1):
            self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES ({i},'{order}',91001,'{order}',REPEAT('0',64),'RSV-{order}','P0-FIXTURE',841002,
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_item
(id,order_id,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,payable_amount,created_at)
VALUES ({i},{i},841003,841001,'Fixture product','P0-FIXTURE','Fixture SKU','{{}}',39.80,1,1,39.80,39.80,UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_address_snapshot
(id,order_id,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,created_at)
VALUES ({i},{i},841004,'Fixture Receiver','00000000000','Test province','Test city','Test district',
 'Fixture street','000000',UTC_TIMESTAMP(3));
""")
        self.service("payment", {**{
            f"ecommerce.payment.{part}.enabled": "false"
            for part in ("outbox", "refund-consumer", "refund-dispatch", "reconciliation")
        }, "ecommerce.payment.client.service-discovery-enabled": "false",
            "ecommerce.payment.client.trade-base-url": "http://trade:18104"})
        self.host_check("host-apps-ready.json")
        self.customer = {"Authorization": "Bearer " + base.runtime.token("CUSTOMER")}
        for order in ORDERS:
            values = self.facts(order)
            self.save(order + "-initial-ledgers.json", values)
            assert values["orderState"] == "PENDING_PAYMENT" and values["payments"] == 0

        self.stage = "P0-01-response-lost"
        order = ORDERS[0]
        key = "IDEM-" + order
        dropped = json.loads(self.docker("exec", "-i", self.http, "node", "-e", DROP_RESPONSE, stdin=json.dumps({
            "target": "http://payment:18105" + PAYMENT_PATH, "headers": {**self.headers(key), "Content-Type": "application/json"},
            "body": {"orderNo": order, "channel": "MOCK"}
        }), timeout=15))
        self.save(order + "-transport.json", dropped)
        assert dropped["closed"] is True and not dropped.get("handlerError")
        assert dropped["upstream"]["status"] == 200 and dropped["upstream"]["code"] == "OK"
        payment_no = dropped["upstream"]["paymentNo"]
        lookups = [self.data(order + "-by-key", self.get("payment", PAYMENT_PATH + "/by-idempotency-key/" + key,
                                                            headers=self.customer)),
                   self.data(order + "-by-order", self.get("payment", PAYMENT_PATH + "/by-order/" + order,
                                                               headers=self.customer)),
                   self.create(order, key, order + "-original-key-retry")]
        assert all(item["paymentNo"] == payment_no and item["status"] == "PROCESSING" and item["amount"] == 39.8 for item in lookups)
        pending = self.facts(order)
        self.save(order + "-pending-ledgers.json", pending)
        assert pending["payments"] == 1 and pending["transactions"] == pending["callbacks"] == pending["successEvents"] == 0
        self.callback(order, payment_no, 1)
        self.assert_settled(order, payment_no)
        self.stage = "P0-02-duplicate-payment"
        order = ORDERS[1]
        key = "IDEM-" + order
        alternate = "NEW-" + order
        payment = self.create(order, key, order + "-create")
        payment_no = payment["paymentNo"]
        for n, current in enumerate((key, alternate, key, alternate)):
            update = self.create(order, current, order + f"-retry-{n}")
            assert update["paymentNo"] == payment_no and update["status"] == "PROCESSING"
        self.callback(order, payment_no, 2)
        for n, current in enumerate((key, alternate)):
            update = self.create(order, current, order + f"-retry-after-success-{n}")
            assert update["paymentNo"] == payment_no and update["status"] == "SUCCESS"
        self.assert_settled(order, payment_no)
        self.host_check("host-final.json")
        self.result.update(status="PASS_SCOPED", source=sha, cases=["P0-01", "P0-02"],
                           scope="actual Trade, Payment and MySQL; Mock channel; payment event only persisted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = PaymentCases(parser.parse_args())
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
