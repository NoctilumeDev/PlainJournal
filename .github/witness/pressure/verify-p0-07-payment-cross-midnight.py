"""Run the real service payment and coupon time boundaries on disposable MySQL."""
import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ["PJ_WITNESS_INPUTS"])
spec = importlib.util.spec_from_file_location("existing_payment_cases", HERE / "verify-p0-payment-recovery.py")
payment_cases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payment_cases)
base = payment_cases.base
base.PORTS["marketing"] = 18107
PAYMENT = "/api/v1/payment/payments"
MARKETING = "/api/v1/marketing"
CROSSING_ORDER = "MIDNIGHT-PAY"
EXPIRED_ORDER = "MIDNIGHT-EXPIRED"
BEFORE = datetime(2030, 1, 1, 15, 59, 59, tzinfo=timezone.utc)
AFTER = datetime(2030, 1, 1, 16, 0, 1, tzinfo=timezone.utc)


class Midnight(payment_cases.PaymentCases):
    def start_services(self, clock, label):
        for app, flags in {
            "trade": {
                **{f"ecommerce.trade.{name}.enabled": "false" for name in
                   ("outbox", "payment-consumer", "flash-sale-consumer", "fulfillment-consumer",
                    "after-sale-fulfillment-consumer", "after-sale-inventory-consumer", "refund-result-consumer", "reconciliation")},
                "ecommerce.trade.client.service-discovery-enabled": "false",
                "ecommerce.trade.order.recovery-enabled": "false",
            },
            "payment": {
                **{f"ecommerce.payment.{name}.enabled": "false" for name in ("outbox", "refund-consumer", "refund-dispatch", "reconciliation")},
                "ecommerce.payment.client.service-discovery-enabled": "false",
                "ecommerce.payment.client.trade-base-url": "http://trade:18104",
            },
            "marketing": {
                "ecommerce.marketing.flash-sale.redis-enabled": "false",
                "ecommerce.marketing.flash-sale-outbox.enabled": "false",
                "ecommerce.marketing.flash-sale-result-consumer.enabled": "false",
                "ecommerce.marketing.order-consumer.enabled": "false",
            },
        }.items():
            clock_sql = f"SET SESSION timestamp = {int(clock.timestamp())}"
            self.service(app, {**flags, "spring.datasource.hikari.connection-init-sql": clock_sql}, f"{app}-{label}")
            self.host_check(f"host-{app}-{label}.json")

    def coupon_lock(self, order, benefit):
        return self.get("marketing", MARKETING+"/internal/pricing-locks", {
            "orderNo": order, "userId": 91001, "originalAmount": 50,
            "deliveryRegion": {"provinceCode": "110000", "cityCode": "110100", "districtCode": "110101"},
            "lines": [{"lineNo": 1, "skuId": 843101, "lineAmount": 50}],
            "benefitNos": [benefit],
        }, base.TRADE_HEADER)

    def settle(self, payment):
        stamp = int(AFTER.timestamp())
        event = "MIDNIGHT-EVT"
        transaction = "MIDNIGHT-TXN"
        body = "|".join((payment, event, transaction, "SUCCESS", "39.8", str(stamp)))
        signature = hmac.new(base.runtime.INTERNAL_SECRET.encode(), body.encode(), hashlib.sha256).hexdigest()
        message = {"paymentNo": payment, "externalEventId": event, "externalTransactionNo": transaction,
                   "status": "SUCCESS", "amount": 39.8, "timestamp": stamp, "signature": signature}
        for name in ("first-callback", "duplicate-callback"):
            settled = self.data(name, self.get("payment", "/api/v1/payment/callbacks/mock", message))
            assert settled["status"] == "SUCCESS" and settled["paymentNo"] == payment and settled["amount"] == 39.8

    def execute(self):
        self.stage = "source-and-isolation"
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        assert sha == json.loads((INPUTS/"coordinates.json").read_text())["candidate"]
        assert not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.jars = json.loads((INPUTS/"candidate-jars.json").read_text())
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql = self.image(os.environ["PJ_MYSQL_REF"])
        node = self.image(os.environ["PJ_NODE_REF"])
        self.save("coordinates.json", {"source":sha, "java":self.java, "mysql":mysql, "fixture_client":node,
                                       "jars":self.jars, "witness":hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.mysql = self.container("mysql", mysql, [
            "--network-alias", "mysql", "--memory", "700m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=600m", "-e", "MYSQL_ROOT_PASSWORD=" + base.runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC",
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        assert self.sql("SELECT VERSION()") == "8.4.10"
        self.sql("CREATE DATABASE b1_trade; CREATE DATABASE b1_payment; CREATE DATABASE b1_marketing;")
        self.http = self.container("http", node, ["--memory", "96m", "--cpus", "1"], ["sleep", "3600"])
        self.customer = {"Authorization": "Bearer " + base.runtime.token("CUSTOMER")}
        admin = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}

        self.stage = "before-midnight-business-time"
        self.start_services(BEFORE, "before")
        self.seed([CROSSING_ORDER, EXPIRED_ORDER])
        self.sql(f"""
UPDATE b1_trade.trade_order SET payment_deadline='2030-01-01 16:02:00'
WHERE order_no='{CROSSING_ORDER}';
UPDATE b1_trade.trade_order SET payment_deadline='2030-01-01 16:00:00'
WHERE order_no='{EXPIRED_ORDER}';
""")
        checkout = self.create(CROSSING_ORDER, "MIDNIGHT-IDEM", "payment-before")
        assert checkout["status"] == "PROCESSING" and checkout["amount"] == 39.8
        self.data("time-rule", self.get("marketing", MARKETING+"/admin/rules", {
            "ruleCode":"TIME-BOUNDARY","name":"Synthetic midnight cutoff","benefitType":"COUPON",
            "thresholdAmount":30,"discountAmount":10,"stackOrder":1,
            "validFrom":"2030-01-01T15:00:00Z","validUntil":"2030-01-01T16:00:00Z","regions":[],
        }, admin))
        benefits = []
        for case in ("before", "after"):
            granted = self.data("grant-"+case, self.get("marketing", MARKETING+"/admin/benefits", {
                "userId":91001,"ruleCode":"TIME-BOUNDARY","grantKey":"MIDNIGHT-"+case,
            }, admin))
            benefits.append(granted["benefitNo"])
        before = self.data("coupon-before", self.coupon_lock("MIDNIGHT-OFFER-BEFORE", benefits[0]))
        assert before["couponDiscount"] == 10 and before["payableAmount"] == 40
        for app in ("payment", "marketing", "trade"):
            self.stop(self.apps[app])

        self.stage = "after-midnight-business-time"
        self.start_services(AFTER, "after")
        self.settle(checkout["paymentNo"])
        settled = self.data("payment-after", self.get("payment", PAYMENT+"/"+checkout["paymentNo"], headers=self.customer))
        assert settled["status"] == "SUCCESS" and settled["amount"] == 39.8
        expired = self.get("payment", PAYMENT, {"orderNo": EXPIRED_ORDER, "channel": "MOCK"}, self.headers("MIDNIGHT-EXPIRED"))
        self.save("expired-order-refused.json", expired)
        assert (expired["status"], expired["body"]["code"]) == (409, "INVALID_STATE")
        coupon = self.coupon_lock("MIDNIGHT-OFFER-AFTER", benefits[1])
        self.save("expired-coupon-refused.json", coupon)
        assert (coupon["status"], coupon["body"]["code"]) == (409, "BENEFIT_NOT_ELIGIBLE")
        ledger = self.facts(CROSSING_ORDER)
        self.save("settled-payment-ledger.json", ledger)
        assert ledger["payments"] == ledger["transactions"] == ledger["callbacks"] == ledger["successEvents"] == 1
        assert ledger["successfulAmount"] == 39.8 and ledger["refunds"] == 0
        expired_ledger = self.facts(EXPIRED_ORDER)
        self.save("expired-order-ledger.json", expired_ledger)
        assert expired_ledger["payments"] == expired_ledger["transactions"] == expired_ledger["refunds"] == 0
        stamps = self.sql(f"""
SELECT DATE_FORMAT(p.created_at,'%Y-%m-%d %H:%i:%s'),
       DATE_FORMAT(c.received_at,'%Y-%m-%d %H:%i:%s')
FROM b1_payment.payment_order p JOIN b1_payment.payment_callback_log c ON c.payment_no=p.payment_no
WHERE p.order_no='{CROSSING_ORDER}';
""").split("\t")
        assert stamps == ["2030-01-01 15:59:59", "2030-01-01 16:00:01"]
        self.save("business-clock-readback.json", {"createdUtc":stamps[0],"receivedUtc":stamps[1],"zone":"+08:00",
                                                  "checkoutLocal":"2030-01-01 23:59:59","callbackLocal":"2030-01-02 00:00:01"})
        self.host_check("host-final.json")
        self.result.update(status="PASS_SCOPED", source=sha, scenario="P0-07", stage=self.stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    scenario = Midnight(parser.parse_args())
    try:
        scenario.execute()
    except Exception as error:
        scenario.result.update(stage=scenario.stage, error=repr(error))
    finally:
        scenario.cleanup()
        scenario.save("result.json", scenario.result)
    print(json.dumps(scenario.result))
    return 0 if scenario.result["status"] == "PASS_SCOPED" and scenario.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())