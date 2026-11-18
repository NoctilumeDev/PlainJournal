"""Exercise real Payment, RocketMQ, Trade and Inventory on exact Boot 4.1.2 JARs."""
import argparse
from datetime import datetime, timedelta, timezone
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
REPO = Path(os.environ["PJ_SOURCE"])
spec = importlib.util.spec_from_file_location("runtime_runner", REPO / "backend/tools/run-payment-slow-boundary.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

ORDER = "BOOT412-PAY-01"
RESERVATION = "RSV-" + ORDER
PAYMENT_EVENT = "MOCK-EVT-" + ORDER
PAYMENT_TRANSACTION = "MOCK-TXN-" + ORDER
SKU = 841001
WAREHOUSE = 841002
PORTS = {"inventory": 18103, "trade": 18104, "payment": 18105}
TRADE_HEADER = {"X-Internal-Service": "trade-service", "X-Internal-Token": runtime.INTERNAL_SECRET}
NODE_REQUEST = """
const fs = require('node:fs');
const req = JSON.parse(fs.readFileSync(0, 'utf8'));
const options = { headers: req.headers, signal: AbortSignal.timeout(9000) };
if (req.body !== null) {
  options.method = 'POST';
  options.body = JSON.stringify(req.body);
  options.headers['Content-Type'] = 'application/json';
}
fetch(req.url, options).then(async r => {
  const body = await r.text();
  let data;
  try { data = JSON.parse(body); } catch { data = body; }
  console.log(JSON.stringify({ status: r.status, body: data }));
}).catch(e => { console.error(e.message, e.cause?.code); process.exitCode = 1; });
"""


class Chain(runtime.Run):
    def __init__(self, args):
        super().__init__(args)
        self.ident = self.ident.replace("pj-b1-", "pj-boot412-pay-")
        self.result["id"] = self.ident
        self.apps = {}

    def host_check(self, name):
        snapshot = {}
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            key, value = line.split(":", 1)
            if key in ("MemAvailable", "MemTotal"):
                snapshot[key] = int(value.split()[0])
        available = round(snapshot["MemAvailable"] / 1048576, 2)
        total = round(snapshot["MemTotal"] / 1048576, 2)
        used = 1 - available / total
        floor_gib = 3.0
        maximum_used = 0.82
        low, high = map(int, Path("/proc/sys/net/ipv4/ip_local_port_range").read_text().split())
        port_fractions = {}
        for kind, flags in (("tcp", "-tan"), ("udp", "-uan")):
            output = self.command("ss", "-H", "-4", flags)
            local_ports = [row.split()[3].rsplit(":", 1)[1] for row in output.splitlines()]
            ports = {int(port) for port in local_ports if port.isdecimal()}
            port_fractions[kind] = sum(low <= port <= high for port in ports) / (high - low + 1)
        engine = self.docker("info", "--format", "{{.OSType}}")
        passed = (available >= floor_gib and used < maximum_used
                  and all(fraction < 0.8 for fraction in port_fractions.values())
                  and engine == "linux")
        self.save(name, {"os": "linux", "memoryAvailableGiB": available, "memoryTotalGiB": total,
                         "memoryUsedFraction": used, "minimumAvailableGiB": floor_gib,
                         "maximumMemoryUsedFraction": maximum_used, "ipv4DynamicPortUse": port_fractions,
                         "docker": engine, "passed": passed})
        if not passed:
            raise RuntimeError("runner resource preflight rejected this topology")

    def image(self, ref):
        return self.docker("image", "inspect", "--format", "{{.Id}}", ref)

    def get(self, app, path, body=None, headers=None):
        request = {"url": f"http://{app}:{PORTS[app]}{path}", "body": body, "headers": headers or {}}
        return json.loads(self.docker("exec", "-i", self.http, "node", "-e", NODE_REQUEST,
                                      stdin=json.dumps(request), timeout=13))

    def data(self, label, response):
        self.save(label + ".json", response)
        assert response["status"] == 200 and response["body"]["code"] == "OK", label
        return response["body"]["data"]

    def ready(self, app):
        self.wait_for(lambda: self.get(app, "/actuator/health/readiness")["body"].get("status") == "UP",
                      100, f"{app} readiness")

    def stop(self, cid):
        self.docker("stop", "-t", "12", cid, timeout=32)

    def service(self, app, overrides, fixture_name=None):
        name = fixture_name or app
        path = f"backend/services/{app}-service/target/{app}-service.jar"
        jar = REPO / path
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == self.jars[path], app
        jdbc = (f"jdbc:mysql://mysql:3306/b1_{app}?connectionTimeZone=UTC"
                "&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true")
        app_args = [
            "--spring.config.import=", "--spring.cloud.nacos.config.enabled=false",
            "--spring.cloud.nacos.discovery.enabled=false", "--spring.datasource.url=" + jdbc,
            "--spring.datasource.username=root", "--spring.datasource.password=" + runtime.DB_SECRET,
            "--spring.datasource.hikari.maximum-pool-size=4",
            "--ecommerce.messaging.consumer-failure-retry.enabled=false",
            "--management.tracing.export.otlp.enabled=false",
            *[f"--{name}={value}" for name, value in overrides.items()],
        ]
        self.apps[app] = self.container(name, self.java, [
            "--network-alias", app, "--memory", "570m", "--cpus", "1.5",
            "--mount", f"type=bind,source={jar},target=/app.jar,readonly",
            "-e", "IDENTITY_JWT_SECRET=" + runtime.TOKEN_SECRET,
            "-e", "PAYMENT_INTERNAL_SERVICE_TOKEN=" + runtime.INTERNAL_SECRET,
            "-e", "TRADE_INTERNAL_SERVICE_TOKEN=" + runtime.INTERNAL_SECRET,
            "-e", "METRICS_SCRAPE_TOKEN=" + runtime.INTERNAL_SECRET,
            "-e", "MOCK_PAYMENT_CALLBACK_SECRET=" + runtime.INTERNAL_SECRET,
            "-e", "ROCKETMQ_PROXY_ENDPOINT=broker:18082",
        ], ["java", "-Xms48m", "-Xmx288m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2",
            "-jar", "/app.jar", *app_args])
        self.ready(app)
        readback = self.docker("exec", self.apps[app], "sha256sum", "/app.jar").split()[0]
        assert readback == self.jars[path], app
        java = self.docker("exec", self.apps[app], "sh", "-c", "java -version 2>&1")
        assert 'version "25.0.5"' in java
        self.save(name + "-artifact.json", {"java": java, "image": self.java, "jar_sha256": readback,
                                          "container": self.apps[app]})

    def admin(self, *args, check=True):
        return self.docker("exec", self.broker, "sh", "mqadmin", *args, "-n", "namesrv:9876",
                           timeout=40, check=check)

    def start_broker(self, image):
        self.namesrv = self.container("namesrv", image, [
            "--network-alias", "namesrv", "--memory", "330m", "--cpus", "1.5",
            "-e", "JAVA_OPT_EXT=-Xms48m -Xmx160m -Xmn48m -XX:ActiveProcessorCount=2",
        ], ["sh", "mqnamesrv"])
        config = self.directory / "broker.conf"
        config.write_text(
            "brokerClusterName=EcommerceCluster\nbrokerName=broker-a\nbrokerId=0\n"
            "brokerIP1=127.0.0.1\nlistenPort=10911\nbrokerRole=ASYNC_MASTER\n"
            "flushDiskType=ASYNC_FLUSH\nstorePathRootDir=/tmp/pj-store\n"
            "storePathCommitLog=/tmp/pj-store/commitlog\nmapedFileSizeCommitLog=16777216\n"
            "autoCreateTopicEnable=false\nautoCreateSubscriptionGroup=true\nenablePropertyFilter=true\n",
            encoding="utf-8")
        proxy_config = self.directory / "rmq-proxy.json"
        proxy_config.write_text(json.dumps({"grpcServerPort": 18082, "rocketMQClusterName": "EcommerceCluster",
                                           "useEndpointPortFromRequest": True}), encoding="utf-8")
        self.broker = self.container("broker", image, [
            "--network-alias", "broker", "--memory", "740m", "--cpus", "1.5",
            "-e", "NAMESRV_ADDR=namesrv:9876",
            "-e", "JAVA_OPT_EXT=-Xms128m -Xmx416m -Xmn96m -XX:ActiveProcessorCount=2",
            "--mount", f"type=bind,source={config},target=/tmp/broker.conf,readonly",
        ], ["sh", "mqbroker", "-c", "/tmp/broker.conf"])
        self.wait_for(lambda: "EcommerceCluster" in self.admin("clusterList", check=False),
                      95, "RocketMQ broker registration")
        self.proxy = self.docker("run", "-d", "--pull=never", "--name", self.ident + "-proxy",
                                 "--label", "codex.task=" + self.ident, "--network", "container:" + self.broker,
                                 "--memory", "435m", "--cpus", "1.5",
                                 "-e", "JAVA_OPT_EXT=-Xms64m -Xmx256m -Xmn64m -XX:ActiveProcessorCount=2",
                                 "--mount", f"type=bind,source={proxy_config},target=/tmp/proxy.json,readonly",
                                 image, "sh", "mqproxy", "-n", "namesrv:9876",
                                 "-pc", "/tmp/proxy.json", "-pm", "cluster")
        self.containers.append(self.proxy)
        self.wait_for(lambda: any(":" + f"{18082:04X}" in line and line.split()[3] == "0A"
                                   for line in self.docker("exec", self.broker, "cat", "/proc/net/tcp",
                                                           "/proc/net/tcp6").splitlines()),
                      80, "isolated RocketMQ Proxy")
        self.host_check("host-broker-ready.json")
        observed = self.admin("updateTopic", "-c", "EcommerceCluster", "-t", "ecommerce-payment-events",
                              "-r", "4", "-w", "4")
        assert "success" in observed.lower()
        self.save("rocketmq-topic.json", {"cluster": self.admin("clusterList"), "created": observed})

    def snapshot(self):
        sql = f"""
SELECT JSON_OBJECT(
 'order', (SELECT status FROM b1_trade.trade_order WHERE order_no='{ORDER}'),
 'linkedPayment', (SELECT payment_no FROM b1_trade.trade_order WHERE order_no='{ORDER}'),
 'recoveryAttempts', (SELECT recovery_attempts FROM b1_trade.trade_order WHERE order_no='{ORDER}'),
 'nextRecoveryAt', (SELECT next_recovery_at FROM b1_trade.trade_order WHERE order_no='{ORDER}'),
 'lastRecoveryError', (SELECT last_error FROM b1_trade.trade_order WHERE order_no='{ORDER}'),
 'addressSnapshotCount', (SELECT COUNT(*) FROM b1_trade.order_address_snapshot WHERE order_id=1),
 'payment', (SELECT status FROM b1_payment.payment_order WHERE order_no='{ORDER}'),
 'transactionCount', (SELECT COUNT(*) FROM b1_payment.payment_transaction WHERE channel_transaction_no='{PAYMENT_TRANSACTION}'),
 'transactionAmount', (SELECT amount FROM b1_payment.payment_transaction WHERE channel_transaction_no='{PAYMENT_TRANSACTION}'),
 'callbackCount', (SELECT COUNT(*) FROM b1_payment.payment_callback_log WHERE external_event_id='{PAYMENT_EVENT}'),
 'paymentOutbox', (SELECT status FROM b1_payment.outbox_event WHERE event_type='PaymentSucceeded'),
 'paymentEventCount', (SELECT COUNT(*) FROM b1_payment.outbox_event WHERE event_type='PaymentSucceeded'),
 'paymentReceipts', (SELECT COUNT(*) FROM b1_trade.consumed_event
   WHERE event_id=(SELECT id FROM b1_payment.outbox_event WHERE event_type='PaymentSucceeded')),
 'pendingTransitions', (SELECT COUNT(*) FROM b1_trade.order_status_history WHERE order_id=1 AND command='PAYMENT_SUCCEEDED'),
 'paidTransitions', (SELECT COUNT(*) FROM b1_trade.order_status_history WHERE order_id=1 AND command='CONFIRM_PAID_INVENTORY'),
 'orderPaidCount', (SELECT COUNT(*) FROM b1_trade.outbox_event WHERE aggregate_id='{ORDER}' AND event_type='OrderPaid'),
 'reservation', (SELECT status FROM b1_inventory.inventory_reservation WHERE reservation_no='{RESERVATION}'),
 'onHand', (SELECT on_hand FROM b1_inventory.inventory_balance WHERE sku_id={SKU} AND warehouse_id={WAREHOUSE}),
 'held', (SELECT reserved FROM b1_inventory.inventory_balance WHERE sku_id={SKU} AND warehouse_id={WAREHOUSE}),
 'confirmMovements', (SELECT COUNT(*) FROM b1_inventory.stock_movement WHERE reservation_no='{RESERVATION}' AND movement_type='CONFIRM'));
"""
        return json.loads(self.sql(sql))

    def execute(self):
        self.stage = "source-and-host"
        sha = self.command("git", "-C", str(REPO), "rev-parse", "HEAD")
        candidate = json.loads((INPUTS / "coordinates.json").read_text(encoding="utf-8-sig"))["candidate"]
        assert sha == candidate and not self.command("git", "-C", str(REPO), "status", "--porcelain")
        self.host_check("host-before.json")
        self.jars = json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql_image = self.image(os.environ["PJ_MYSQL_REF"])
        broker_image = self.image(os.environ["PJ_ROCKETMQ_REF"])
        node_image = self.image(os.environ["PJ_NODE_REF"])
        self.save("coordinates.json", {"source": sha, "java": self.java, "mysql": mysql_image,
                                      "rocketmq": broker_image, "fixture_client": node_image,
                                      "runner": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                                      "jars": self.jars})
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.stage = "database-and-inventory"
        self.mysql = self.container("mysql", mysql_image, [
            "--network-alias", "mysql", "--memory", "760m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=600m", "-e", "MYSQL_ROOT_PASSWORD=" + runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC",
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.sql("CREATE DATABASE b1_inventory; CREATE DATABASE b1_trade; CREATE DATABASE b1_payment;")
        self.http = self.container("http", node_image, ["--memory", "96m", "--cpus", "1"], ["sleep", "3600"])
        self.service("inventory", {
            f"ecommerce.inventory.{feature}.enabled": "false"
            for feature in ("outbox", "order-consumer", "return-consumer", "reconciliation")
        })
        self.host_check("host-inventory-seed.json")
        self.sql(f"""
INSERT INTO b1_inventory.warehouse(id,code,name,status,version,created_at,updated_at)
VALUES ({WAREHOUSE},'BOOT412','Boot qualification','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_inventory.inventory_balance(id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES (1,{WAREHOUSE},{SKU},10,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
        expires = (datetime.now(timezone.utc) + timedelta(minutes=20)).isoformat()
        reservation = self.data("inventory-reserve", self.get("inventory", "/api/v1/inventory/internal/reservations",
            {"reservationNo": RESERVATION, "orderNo": ORDER, "warehouseId": WAREHOUSE,
             "expiresAt": expires, "items": [{"skuId": SKU, "quantity": 1}]}, TRADE_HEADER))
        assert reservation["status"] == "RESERVED"
        self.stop(self.apps["inventory"])
        self.stage = "payment-create"
        trade_overrides = {f"ecommerce.trade.{feature}.enabled": "false" for feature in
                           ("outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
                            "after-sale-fulfillment-consumer", "after-sale-inventory-consumer",
                            "refund-result-consumer", "reconciliation")}
        trade_overrides.update({"ecommerce.trade.client.service-discovery-enabled": "false",
                                "ecommerce.trade.client.inventory-base-url": "http://inventory:18103",
                                "ecommerce.trade.order.recovery-enabled": "true"})
        prep = {**trade_overrides, "ecommerce.trade.order.recovery-enabled": "false"}
        self.service("trade", prep, "trade-prep")
        trade_prep = self.apps["trade"]
        self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES (1,'{ORDER}',91001,'{ORDER}',REPEAT('0',64),'{RESERVATION}','BOOT412',{WAREHOUSE},
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_item
(id,order_id,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,payable_amount,created_at)
VALUES (1,1,841003,{SKU},'Fixture product','BOOT-TEST','Fixture SKU','{{}}',39.80,1,1,39.80,39.80,UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_address_snapshot
(id,order_id,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,created_at)
VALUES (1,1,841004,'Fixture Receiver','00000000000','Test province','Test city','Test district',
 'Fixture street','000000',UTC_TIMESTAMP(3));
""")
        customer_headers = {"Authorization": "Bearer " + runtime.token("CUSTOMER")}
        order_path = "/api/v1/trade/orders/" + ORDER
        order = self.data("trade-order-precheck", self.get("trade", order_path, headers=customer_headers))
        assert order["status"] == "PENDING_PAYMENT" and order["deliveryAddress"]["recipientName"] == "Fixture Receiver"
        payment_overrides = {f"ecommerce.payment.{feature}.enabled": "false" for feature in
                             ("refund-consumer", "refund-dispatch", "reconciliation")}
        payment_overrides.update({"ecommerce.payment.client.service-discovery-enabled": "false",
                                  "ecommerce.payment.client.trade-base-url": "http://trade:18104"})
        self.service("payment", payment_overrides)
        self.host_check("host-payment-create.json")
        payment_headers = {"Authorization": "Bearer " + runtime.token("CUSTOMER"),
                           "Idempotency-Key": "IDEM-" + ORDER}
        payment = self.data("payment-create", self.get("payment", "/api/v1/payment/payments",
            {"orderNo": ORDER, "channel": "MOCK"}, payment_headers))
        assert payment["amount"] == 39.8
        payment_no = payment["paymentNo"]
        self.stop(trade_prep)
        self.stage = "payment-success-and-publish"
        self.start_broker(broker_image)
        stamp = int(time.time())
        message = "|".join((payment_no, PAYMENT_EVENT, PAYMENT_TRANSACTION, "SUCCESS", "39.8", str(stamp)))
        signature = hmac.new(runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        callback = {"paymentNo": payment_no, "externalEventId": PAYMENT_EVENT,
                    "externalTransactionNo": PAYMENT_TRANSACTION, "status": "SUCCESS",
                    "amount": 39.8, "timestamp": stamp, "signature": signature}
        for label in ("callback", "duplicate-callback"):
            paid = self.data(label, self.get("payment", "/api/v1/payment/callbacks/mock", callback))
            assert paid["status"] == "SUCCESS" and paid["paymentNo"] == payment_no
        self.wait_for(lambda: self.snapshot()["paymentOutbox"] == "PUBLISHED", 85, "durable payment publication")
        published = self.snapshot()
        self.save("payment-published.json", published)
        assert published["transactionCount"] == published["callbackCount"] == published["paymentEventCount"] == 1
        assert published["transactionAmount"] == 39.8 and published["order"] == "PENDING_PAYMENT"
        self.stop(self.apps["payment"])
        self.stage = "trade-awaits-inventory"
        self.service("trade", {**trade_overrides, "ecommerce.trade.payment-consumer.enabled": "true"})
        self.host_check("host-trade-consumer.json")
        self.wait_for(lambda: self.snapshot()["order"] == "PAYMENT_CONFIRMING", 75, "real payment consumption")
        pending = self.snapshot()
        self.save("inventory-offline-pending.json", pending)
        assert pending["linkedPayment"] == payment_no
        assert pending["pendingTransitions"] == pending["paymentReceipts"] == 1
        assert pending["paidTransitions"] == pending["orderPaidCount"] == pending["confirmMovements"] == 0
        assert (pending["reservation"], pending["onHand"], pending["held"]) == ("RESERVED", 10, 1)
        self.stage = "inventory-recovery"
        for cid in (self.proxy, self.broker, self.namesrv):
            self.stop(cid)
        self.docker("start", self.apps["inventory"])
        self.ready("inventory")
        self.host_check("host-inventory-recovery.json")
        try:
            self.wait_for(lambda: self.snapshot()["order"] == "PAID", 100, "inventory recovery and trade advancement")
        except RuntimeError:
            self.save("recovery-timeout-ledgers.json", self.snapshot())
            self.save("inventory-recovery-readback.json", self.get("inventory",
                "/api/v1/inventory/internal/reservations/" + RESERVATION, headers=TRADE_HEADER))
            raise
        final = self.snapshot()
        self.save("confirmed-ledgers.json", final)
        assert final["transactionCount"] == final["callbackCount"] == final["paymentEventCount"] == 1
        assert final["paymentReceipts"] == final["pendingTransitions"] == final["paidTransitions"] == 1
        assert final["orderPaidCount"] == final["confirmMovements"] == 1
        assert (final["reservation"], final["onHand"], final["held"]) == ("CONFIRMED", 9, 0)
        assert final["payment"] == "SUCCESS" and final["transactionAmount"] == 39.8
        assert final["linkedPayment"] == payment_no and final["paymentOutbox"] == "PUBLISHED"
        assert final["addressSnapshotCount"] == 1
        order = self.data("trade-order-final", self.get("trade", order_path, headers=customer_headers))
        assert order["status"] == "PAID" and order["totalAmount"] == 39.8
        self.save("mysql-version.json", {"version": self.sql("SELECT VERSION()")})
        self.host_check("host-final.json")
        self.result.update(status="PASS_SCOPED", stage=self.stage, source=sha, order=ORDER, payment=payment_no)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = Chain(parser.parse_args())
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
