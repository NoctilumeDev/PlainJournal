"""Observe an actual checkout across repricing, payment and an exceptional refund."""
import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time
import traceback

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("refund_witness", HERE / "verify-p0-04-exception-refund-staged-settlement.py")
refund = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refund)
base = refund.base

APPS = {"identity": 18101, "catalog": 18102, "inventory": 18103, "trade": 18104, "payment": 18105,
        "marketing": 18107}
OLD_PRICE = 39.8
NEW_PRICE = 59.8
STOCK = 10
REQUEST = base.NODE_REQUEST.replace("options.method = 'POST';", "options.method = req.method;")
REDIS_SETTINGS = {"spring.data.redis.host": "redis", "spring.data.redis.port": 6379}
TRADE_SETTINGS = {
    **{f"ecommerce.trade.{feature}.enabled": "false" for feature in (
        "outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
        "after-sale-fulfillment-consumer", "after-sale-inventory-consumer", "refund-result-consumer", "reconciliation")},
    **{f"ecommerce.trade.client.{app}-base-url": f"http://{app}:{APPS[app]}"
       for app in ("catalog", "identity", "inventory", "marketing")},
    "ecommerce.trade.client.service-discovery-enabled": "false",
    "ecommerce.trade.order.default-warehouse-code": "PRICE-WITNESS",
    "ecommerce.trade.order.recovery-enabled": "true",
}
PAYMENT_SETTINGS = {
    "ecommerce.payment.client.trade-base-url": "http://trade:18104",
    "ecommerce.payment.client.service-discovery-enabled": "false",
    "ecommerce.payment.refund-consumer.enabled": "false",
    "ecommerce.payment.refund-dispatch.enabled": "true",
    "ecommerce.payment.reconciliation.enabled": "false",
}


class PriceCase(refund.RefundCase):
    def fixture_network_options(self):
        return []

    def put(self, app, path, body, headers):
        request = {"url": f"http://{app}:{APPS[app]}{path}", "method": "PUT",
                   "body": body, "headers": headers}
        return json.loads(self.docker("exec", "-i", self.http, "node", "-e", REQUEST,
                                      stdin=json.dumps(request), timeout=13))

    def open_lab(self):
        base.PORTS.update(APPS)
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        candidate = json.loads((base.INPUTS / "coordinates.json").read_text())["candidate"]
        assert sha == candidate and not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.result["source"] = sha
        self.jars = json.loads((base.INPUTS / "candidate-jars.json").read_text())
        images = {name: self.image(os.environ[f"PJ_{name}_REF"])
                  for name in ("JRE", "MYSQL", "ROCKETMQ", "NODE", "REDIS")}
        self.java = images["JRE"]
        self.save("coordinates.json", {"source": sha, "images": images, "jars": self.jars,
                                       "runner": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident,
                    *self.fixture_network_options(), self.ident)
        self.network_created = True
        self.mysql = self.container("mysql", images["MYSQL"], [
            "--network-alias", "mysql", "--memory", "760m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=650m", "-e", "MYSQL_ROOT_PASSWORD=" + base.runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC",
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.save("mysql-version.json", {"version": self.sql("SELECT VERSION()")})
        self.sql(" ".join(f"CREATE DATABASE b1_{app};" for app in APPS))
        self.http = self.container("http", images["NODE"], ["--memory", "96m"], ["sleep", "3600"])
        self.redis = self.container("redis", images["REDIS"], ["--network-alias", "redis", "--memory", "120m"],
                                    ["redis-server", "--save", "", "--appendonly", "no"])
        self.wait_for(lambda: self.docker("exec", self.redis, "redis-cli", "ping") == "PONG", 20, "Redis")
        self.broker_image = images["ROCKETMQ"]

    def setup_customer(self):
        self.service("identity", REDIS_SETTINGS)
        auth = "/api/v1/identity/auth"
        credentials = {"email": "price-witness@example.invalid", "password": "SyntheticTestPass123"}
        registration = self.get("identity", auth + "/register", {**credentials, "displayName": "Price Witness"})
        self.save("registration-http-status.json", {"status": registration["status"]})
        assert registration["status"] == 201 and registration["body"]["code"] == "OK"
        assert "CUSTOMER" in registration["body"]["data"]["roles"]
        login = self.get("identity", auth + "/login", credentials)
        self.save("login-http-status.json", {"status": login["status"]})
        assert login["status"] == 200 and login["body"]["code"] == "OK"
        self.customer = {"Authorization": "Bearer " + login["body"]["data"]["accessToken"]}
        self.address = self.data("customer-address", self.get("identity", "/api/v1/identity/addresses", {
            "recipientName": "Synthetic Customer", "phone": "+86 13800000000",
            "province": "Zhejiang", "provinceCode": "330000", "city": "Hangzhou", "cityCode": "330100",
            "district": "Xihu", "districtCode": "330106", "detailAddress": "Test Fixture Street 1",
            "postalCode": "310000", "setDefault": True,
        }, self.customer))["id"]

    def setup_sku(self):
        self.service("catalog", {**REDIS_SETTINGS, "ecommerce.catalog.cache.enabled": "true",
                                 "ecommerce.catalog.review-events.enabled": "false",
                                 "ecommerce.catalog.search.enabled": "false",
                                 "ecommerce.catalog.media.username": "synthetic-fixture",
                                 "ecommerce.catalog.media.password": "synthetic-fixture-credential"})
        self.admin_headers = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}
        admin = "/api/v1/catalog/admin"
        category = self.data("category-create", self.get("catalog", admin + "/categories", {
            "name": "Witness Category", "slug": "witness-category", "sortOrder": 1,
        }, self.admin_headers))
        brand = self.data("brand-create", self.get("catalog", admin + "/brands",
                                                {"name": "Witness Brand", "slug": "witness-brand"}, self.admin_headers))
        self.sku_input = {"skuCode": "WITNESS-PRICE", "name": "Price fixture", "specJson": '{"variant":"default"}',
                          "salePrice": OLD_PRICE, "marketPrice": 69.8}
        product = self.data("draft", self.get("catalog", admin + "/products", {
            "categoryId": category["id"], "brandId": brand["id"], "title": "Synthetic priced product",
            "subtitle": "Fixture", "description": "Created via admin API", "skus": [self.sku_input],
        }, {**self.admin_headers, "Idempotency-Key": "WITNESS-PRICE-PRODUCT"}))
        assert product["status"] == "DRAFT"
        self.product_id = str(int(product["id"]))
        assert self.get("catalog", "/api/v1/catalog/products/" + self.product_id)["status"] == 404
        published = self.data("published", self.get("catalog", f"{admin}/products/{self.product_id}/publish",
                                                   {"expectedVersion": product["version"]}, self.admin_headers))
        assert published["status"] == "ACTIVE"
        self.sku_id = str(int(published["skus"][0]["id"]))
        self.sku_version = published["skus"][0]["version"]
        self.service("inventory", {f"ecommerce.inventory.{feature}.enabled": "false"
                                   for feature in ("outbox", "order-consumer", "return-consumer", "reconciliation")})
        warehouse = self.data("warehouse-create", self.get("inventory", "/api/v1/inventory/admin/warehouses",
            {"code": "PRICE-WITNESS", "name": "Synthetic warehouse"}, self.admin_headers))
        self.warehouse_id = str(int(warehouse["id"]))
        stock = self.data("initial-stock", self.get("inventory", "/api/v1/inventory/admin/stocks/adjustments", {
            "movementNo": "PRICE-WITNESS-STOCK", "warehouseId": self.warehouse_id, "skuId": self.sku_id,
            "quantityDelta": STOCK, "reason": "Synthetic fixture initialization",
        }, self.admin_headers))
        assert stock["available"] == STOCK
        self.service("marketing", {**REDIS_SETTINGS,
                                   "ecommerce.marketing.flash-sale.redis-enabled": "false",
                                   "ecommerce.marketing.flash-sale-outbox.enabled": "false",
                                   "ecommerce.marketing.flash-sale-result-consumer.enabled": "false",
                                   "ecommerce.marketing.order-consumer.enabled": "false"})
        self.service("trade", {**REDIS_SETTINGS, **TRADE_SETTINGS}, "trade-prep")

    def sample(self, phase, orders, price, held):
        samples = []
        for index in range(4):
            started = datetime.now(timezone.utc).isoformat()
            product = self.data(f"{phase}-{index}-catalog", self.get(
                "catalog", "/api/v1/catalog/products/" + self.product_id))
            sku = product["skus"][0]
            stock = self.data(f"{phase}-{index}-stock", self.get(
                "inventory", "/api/v1/inventory/stocks/" + self.sku_id))
            views = {order: self.data(f"{phase}-{index}-{order}", self.order_state(order)) for order in orders}
            row = self.sql(f"""
SELECT JSON_OBJECT('skuId', CAST(s.id AS CHAR), 'price', s.sale_price, 'skuVersion', s.version,
 'stockVersion', i.version, 'onHand', i.on_hand, 'reserved', i.reserved)
FROM b1_catalog.product_sku s JOIN b1_inventory.inventory_balance i ON s.id=i.sku_id
WHERE s.id={self.sku_id} AND i.warehouse_id={self.warehouse_id};
""")
            db = json.loads(row)
            samples.append({"startedAt": started, "completedAt": datetime.now(timezone.utc).isoformat(),
                            "sku": sku, "stock": stock, "database": db, "orders": views})
            self.save(phase + "-samples.json", samples)
            assert str(sku["id"]) == str(stock["skuId"]) == db["skuId"] == self.sku_id
            assert sku["salePrice"] == db["price"] == price
            assert sku["version"] == db["skuVersion"]
            assert (stock["onHand"], stock["reserved"], stock["available"]) == (STOCK, held, STOCK - held)
            assert (db["onHand"], db["reserved"]) == (STOCK, held)
            for order, expected in orders.items():
                view = views[order]
                assert len(view["items"]) == 1 and str(view["items"][0]["skuId"]) == self.sku_id
                assert view["items"][0]["unitPrice"] == view["items"][0]["payableAmount"] == view["totalAmount"] == expected
            time.sleep(0.4)
        assert len({(row["database"]["skuVersion"], row["database"]["stockVersion"]) for row in samples}) == 1

    def checkout(self, key, price):
        order = self.data(key, self.get("trade", "/api/v1/trade/orders", {
            "addressId": self.address, "items": [{"productId": self.product_id, "skuId": self.sku_id, "quantity": 1}],
            "benefitNos": [],
        }, self.headers(key)))
        assert order["status"] == "PENDING_PAYMENT"
        assert order["items"][0]["unitPrice"] == order["totalAmount"] == price
        number = order["orderNo"]
        assert number.startswith("ORD") and number[3:].isascii() and number[3:].isdigit()
        return number

    def states(self, order):
        facts = self.sql(f"""
SELECT JSON_OBJECT('orderState', o.status, 'linkedPayment', o.payment_no, 'reservation', r.status,
 'stock', b.on_hand, 'reserved', b.reserved,
 'confirmations', (SELECT COUNT(*) FROM b1_inventory.stock_movement
                   WHERE reservation_no=o.reservation_no AND movement_type='CONFIRM'),
 'orderPaid', (SELECT COUNT(*) FROM b1_trade.outbox_event
               WHERE aggregate_id=o.order_no AND event_type='OrderPaid'),
 'paymentEventStatus', (SELECT e.status FROM b1_payment.outbox_event e
                        JOIN b1_payment.payment_order p ON p.payment_no=e.aggregate_id
                        WHERE p.order_no=o.order_no AND e.event_type='PaymentSucceeded'))
FROM b1_trade.trade_order o
JOIN b1_inventory.inventory_reservation r ON r.reservation_no=o.reservation_no
JOIN b1_inventory.inventory_balance b ON b.warehouse_id=o.warehouse_id AND b.sku_id={self.sku_id}
WHERE o.order_no='{order}';
""")
        return json.loads(facts) | {"payment": self.facts(order)}

    def settle_and_refund(self, order):
        self.service("payment", PAYMENT_SETTINGS, "payment-refund")
        pay = self.create(order, "WITNESS-PRICE-PAYMENT", "priced-payment")
        assert pay["amount"] == OLD_PRICE and pay["status"] == "PROCESSING"
        checked = self.data("cancel-before-channel-settlement", self.cancel(order))
        assert checked["status"] == "CANCELED"
        self.wait_for(lambda: self.states(order)["reservation"] == "RELEASED", 40, "released old-price reservation")
        self.stop(self.apps["trade"])
        self.start_broker(self.broker_image)
        self.callback(order, pay["paymentNo"], duplicates=2)
        self.wait_for(lambda: self.states(order)["paymentEventStatus"] == "PUBLISHED", 80, "published payment event")
        self.service("trade", {**REDIS_SETTINGS, **TRADE_SETTINGS,
                               "ecommerce.trade.payment-consumer.enabled": "true",
                               "ecommerce.trade.refund-result-consumer.enabled": "true"}, "trade-consumer")
        self.host_check("host-price-settlement.json")
        self.wait_for(lambda: self.states(order)["orderState"] == "PAYMENT_EXCEPTION", 75, "late receipt reviewed")
        asked = self.data("priced-refund-request", self.get(
            "payment", f"/api/v1/payment/admin/payments/{pay['paymentNo']}/exception-refunds",
            {"reason": "Synthetic late settlement after repricing"},
            {**self.admin_headers, "Idempotency-Key": "WITNESS-PRICE-REFUND"}))
        number = asked["refundNo"]
        assert asked["amount"] == OLD_PRICE
        self.wait_for(lambda: (self.refund_state(order)["refund"] or {}).get("requestStatus") == "SENT",
                      40, "Mock channel refund acknowledges request")
        stamp = int(time.time())
        event = "PRICE-WITNESS-REFUND"
        external = "PRICE-WITNESS-REFUND-TXN"
        message = "|".join((number, event, external, "SUCCESS", str(OLD_PRICE), str(stamp)))
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        self.data("priced-refund-callback", self.get("payment", "/api/v1/payment/callbacks/mock/refunds", {
            "refundNo": number, "externalEventId": event, "externalRefundNo": external,
            "status": "SUCCESS", "amount": OLD_PRICE, "timestamp": stamp, "signature": signature,
        }))
        self.wait_for(lambda: self.states(order)["orderState"] == "CLOSED", 90, "refund closes the old-price order")
        state = self.refund_state(order)
        self.save("frozen-price-financial-ledgers.json", state)
        assert state["payment"]["transactions"] == state["refund"]["transactions"] == state["refund"]["refundReceipts"] == 1
        assert state["payment"]["successfulAmount"] == state["refund"]["amountRefunded"] == OLD_PRICE
        assert state["linkedPayment"] == state["refund"]["originalPaymentNo"] == pay["paymentNo"]
        assert state["refund"]["no"] == state["refund"]["tradeRefundNo"] == number
        assert (state["orderPaid"], state["confirmations"], state["reservation"]) == (0, 0, "RELEASED")
        assert (state["stock"], state["reserved"]) == (STOCK, 1)

    def execute(self):
        self.stage = "owned-topology"
        self.open_lab()
        self.stage = "actual-customer-catalog-and-inventory"
        self.setup_customer()
        self.setup_sku()
        self.host_check("host-checkout.json")
        self.stage = "stable-price-before-change"
        self.sample("before-order", {}, OLD_PRICE, 0)
        old = self.checkout("old-price-order", OLD_PRICE)
        self.sample("before-reprice", {old: OLD_PRICE}, OLD_PRICE, 1)

        self.stage = "actual-admin-repricing"
        path = f"/api/v1/catalog/admin/products/{self.product_id}/skus/{self.sku_id}"
        changed = self.data("sku-reprice", self.put("catalog", path, {
            "name": self.sku_input["name"], "specJson": self.sku_input["specJson"], "salePrice": NEW_PRICE,
            "marketPrice": self.sku_input["marketPrice"], "status": "ACTIVE", "expectedVersion": self.sku_version,
        }, self.admin_headers))
        assert str(changed["id"]) == self.sku_id
        assert changed["version"] > self.sku_version and changed["salePrice"] == NEW_PRICE
        self.sample("after-reprice", {old: OLD_PRICE}, NEW_PRICE, 1)
        new = self.checkout("new-price-order", NEW_PRICE)
        assert old != new
        self.sample("after-new-order", {old: OLD_PRICE, new: NEW_PRICE}, NEW_PRICE, 2)
        redis_keys = self.docker("exec", self.redis, "redis-cli", "--scan")
        relevant_keys = sorted(key for key in redis_keys.splitlines() if self.product_id in key)
        self.save("catalog-cache-readback.json", {"redisKeysForProduct": relevant_keys})
        assert relevant_keys, "product detail reached actual Redis"
        self.result["cases"] = ["P1-04-stable-window-catalog-inventory-checkout-sampling"]
        for app in ("catalog", "identity"):
            self.stop(self.apps[app])
        self.stage = "payment-and-refund-of-frozen-order"
        self.settle_and_refund(old)
        final = self.data("old-order-after-refund", self.order_state(old))
        assert final["status"] == "CLOSED" and final["totalAmount"] == OLD_PRICE
        self.result["cases"].append("P1-03-price-frozen-through-payment-and-refund")
        self.result.update(status="PASS_SCOPED", product=self.product_id, sku=self.sku_id,
                           old_order=old, new_order=new,
                           price_change=[f"{OLD_PRICE:.2f}", f"{NEW_PRICE:.2f}"],
                           scope="version-aligned stable reads and actual checkout; late-settlement refund; no replica or media operation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    runner = PriceCase(parser.parse_args())
    try:
        runner.execute()
    except Exception as exc:
        runner.result.update(status="INCONCLUSIVE", stage=runner.stage, error_type=type(exc).__name__)
        runner.save("failure-location.json", [
            {"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
            for frame in traceback.extract_tb(exc.__traceback__)
        ])
    finally:
        runner.cleanup()
        runner.save("result.json", runner.result)
        runner.save("sha256.json", {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                    for path in runner.directory.glob("*.json")
                                    if not path.name.endswith("-stopped.json")})
    print(json.dumps(runner.result, ensure_ascii=False))
    return 0 if runner.result["status"] == "PASS_SCOPED" and runner.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
