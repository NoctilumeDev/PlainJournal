import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location("staged_shipment", HERE / "verify-p0-11-12-shipment-staged-settlement.py")
shipment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shipment)
base = shipment.base
base.PORTS["marketing"] = 18107
JAR = "backend/services/marketing-service/target/marketing-service.jar"
DIGEST = json.loads((INPUTS / "candidate-jars.json").read_text())[JAR]
LEGACY_ROOT = Path(os.environ["PJ_LEGACY_SOURCE"])
LEGACY_DIGEST = json.loads((INPUTS / "legacy-jars.json").read_text())[JAR]

API = "/api/v1/marketing"
SKU = 843101
WAREHOUSE = 843199


class CouponCase(shipment.ShipmentCases):
    def container(self, suffix, image, options, command):
        if suffix in {"marketing-grant", "marketing-lifecycle", "trade-coupon-cancel", "marketing-legacy"}:
            command = ["-Xmx224m" if arg == "-Xmx288m" else arg for arg in command]
        return super().container(suffix, image, options, command)

    def marketing(self, suffix, consumer):
        self.service("marketing", {"ecommerce.marketing.flash-sale.redis-enabled": "false",
                                    "ecommerce.marketing.flash-sale-outbox.enabled": "false",
                                    "ecommerce.marketing.flash-sale-result-consumer.enabled": "false",
                                    "ecommerce.marketing.order-consumer.enabled": "true" if consumer else "false"}, suffix)

    def benefit(self, number):
        return json.loads(self.sql(f"""
SELECT JSON_OBJECT('status',status,'lock',locked_order_no,'lockedAt',locked_at,'redeem',redeemed_order_no,'version',version)
FROM b1_marketing.user_benefit WHERE benefit_no='{number}';
"""))

    def lock(self, order, number):
        return self.get("marketing", API + "/internal/pricing-locks", {
            "orderNo": order, "userId": 91001, "originalAmount": 50,
            "deliveryRegion": {"provinceCode":"110000","cityCode":"110100","districtCode":"110101"},
            "lines":[{"lineNo":1,"skuId":SKU,"lineAmount":50}],"benefitNos":[number],
        }, base.TRADE_HEADER)

    def event_facts(self, order):
        return json.loads(self.sql(f"""
SELECT JSON_OBJECT(
 'trade',(SELECT status FROM b1_trade.trade_order WHERE order_no='{order}'),
 'inventory',(SELECT status FROM b1_inventory.inventory_reservation WHERE order_no='{order}'),
 'cancellations',(SELECT COUNT(*) FROM b1_trade.outbox_event
  WHERE aggregate_id='{order}' AND event_type='OrderCanceled' AND status='PUBLISHED'),
 'receipts',(SELECT COUNT(*) FROM b1_marketing.consumed_event c
  JOIN b1_trade.outbox_event e ON c.event_id=e.id
  WHERE e.aggregate_id='{order}' AND e.event_type='OrderCanceled'),
 'releases',(SELECT COUNT(*) FROM b1_inventory.stock_movement WHERE reservation_no='RSV-{order}' AND movement_type='RELEASE'),
 'lockState',(SELECT status FROM b1_marketing.pricing_lock WHERE order_no='{order}'));
"""))

    def setup(self):
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        coordinate = json.loads((INPUTS / "coordinates.json").read_text())["candidate"]
        assert sha == coordinate and not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.jars = json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        self.jars[JAR] = DIGEST
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql = self.image(os.environ["PJ_MYSQL_REF"])
        node = self.image(os.environ["PJ_NODE_REF"])
        self.broker_image = self.image(os.environ["PJ_ROCKETMQ_REF"])
        self.save("coordinates.json", {"source": sha, "images": [self.java,mysql,node,self.broker_image],
            "jars":self.jars, "test_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.mysql = self.container("mysql", mysql, [
            "--network-alias","mysql","--memory","650m","--cpus","1.5",
            "--tmpfs","/var/lib/mysql:rw,size=560m","-e","MYSQL_ROOT_PASSWORD="+base.runtime.DB_SECRET,
            "-e","MYSQL_ROOT_HOST=%","-e","TZ=UTC"
        ],["--default-time-zone=+00:00","--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda:self.sql("SELECT 1") == "1",75,"isolated MySQL")
        self.save("mysql-version.json",{"version":self.sql("SELECT VERSION()")})
        self.sql("CREATE DATABASE b1_marketing; CREATE DATABASE b1_inventory; CREATE DATABASE b1_trade;")
        self.http=self.container("http",node,["--memory","96m","--cpus","1"],["sleep","3600"])
        self.customer={"Authorization":"Bearer "+base.runtime.token("CUSTOMER")}
        self.result["source"]=sha

    def prepare_historical_rows(self):
        self.stage = "legacy-v7-real-benefit-states"
        legacy_sha = json.loads((INPUTS / "coordinates.json").read_text())["legacy"]
        assert self.command("git", "-C", str(LEGACY_ROOT), "rev-parse", "HEAD") == legacy_sha
        assert not self.command("git", "-C", str(LEGACY_ROOT), "status", "--porcelain")
        candidate_root = base.REPO
        candidate_digest = self.jars[JAR]
        try:
            base.REPO = LEGACY_ROOT
            self.jars[JAR] = LEGACY_DIGEST
            self.marketing("marketing-legacy", False)
        finally:
            base.REPO = candidate_root
            self.jars[JAR] = candidate_digest
        self.host_check("host-legacy-marketing.json")
        assert self.sql("SELECT COUNT(*) FROM b1_marketing.flyway_schema_history WHERE version='7' AND success=1;") == "1"
        assert self.sql("SELECT COUNT(*) FROM b1_marketing.flyway_schema_history WHERE version='8';") == "0"
        admin = {"Authorization": "Bearer " + base.runtime.token("ADMIN")}
        now = datetime.now(timezone.utc)
        self.data("legacy-rule", self.get("marketing", API+"/admin/rules", {
            "ruleCode":"LEGACY-COUPON","name":"Synthetic V7 historical benefit",
            "benefitType":"COUPON","thresholdAmount":30,"discountAmount":10,"stackOrder":1,
            "validFrom":(now-timedelta(minutes=20)).isoformat(),
            "validUntil":(now+timedelta(hours=1)).isoformat(),"regions":[],
        }, admin))
        self.legacy_numbers = {}
        for label, action in (("release","release"),("redeem","redeem"),("locked",None),("available",None)):
            data=self.data("legacy-grant-"+label,self.get("marketing",API+"/admin/benefits",{
                "userId":91001,"ruleCode":"LEGACY-COUPON","grantKey":"LEGACY-"+label
            },admin))
            number=data["benefitNo"]
            self.legacy_numbers[label]=number
            if label != "available":
                order="LEGACY-"+label
                self.data("legacy-lock-"+label,self.lock(order,number))
                if action:
                    self.data("legacy-terminal-"+label,self.get("marketing",
                        API+"/internal/pricing-locks/orders/"+order+"/"+action,{},base.TRADE_HEADER))
        self.old = {label:self.benefit(value) for label,value in self.legacy_numbers.items()}
        self.save("flyway-v7-before.json", self.old)
        assert self.old["release"]["status"]=="AVAILABLE" and self.old["release"]["lock"]=="LEGACY-release"
        assert self.old["redeem"]["status"]=="REDEEMED" and self.old["redeem"]["lock"]=="LEGACY-redeem"
        assert self.old["locked"]["status"]=="LOCKED" and self.old["locked"]["lock"]=="LEGACY-locked"
        assert self.old["available"]["status"]=="AVAILABLE" and self.old["available"]["lock"] is None
        self.stop(self.apps["marketing"])

    def verify_v8_migration(self):
        self.stage = "flyway-v8-clears-only-terminal-rows"
        migrated={label:self.benefit(value) for label,value in self.legacy_numbers.items()}
        history=self.sql("SELECT CONCAT(version,'|',success) FROM b1_marketing.flyway_schema_history WHERE version='8';")
        self.save("flyway-v8-after.json", {"rows":migrated,"history":history})
        assert history=="8|1"
        for label in ("release","redeem"):
            assert migrated[label] == self.old[label] | {"lock":None,"lockedAt":None}
        for label in ("locked","available"):
            assert migrated[label] == self.old[label]
        self.result["migrated_legacy_terminal_rows"] = 2


    def execute(self):
        self.stage = "fixed-candidate"
        self.setup()
        self.prepare_historical_rows()
        self.stage = "concurrent-coupon-lock"
        self.marketing("marketing-grant",False)
        self.verify_v8_migration()
        self.stage = "concurrent-coupon-lock"
        self.host_check("host-lock.json")
        admin={"Authorization":"Bearer "+base.runtime.token("ADMIN")}
        now=datetime.now(timezone.utc)
        rule=self.data("rule",self.get("marketing",API+"/admin/rules",{
            "ruleCode":"P1-COUPON","name":"Synthetic concurrent coupon","benefitType":"COUPON",
            "thresholdAmount":30,"discountAmount":10,"stackOrder":1,
            "validFrom":(now-timedelta(minutes=20)).isoformat(),
            "validUntil":(now+timedelta(hours=1)).isoformat(),"regions":[],
        },admin))
        grant=self.data("grant",self.get("marketing",API+"/admin/benefits",{
            "userId":91001,"ruleCode":rule["ruleCode"],"grantKey":"P1-COUPON-ISSUE"
        },admin))
        number=grant["benefitNo"]
        assert self.benefit(number)["status"] == "AVAILABLE"
        def take(order):
            start=time.monotonic_ns()
            response=self.lock(order,number)
            return {"order":order,"started_ns":start,"ended_ns":time.monotonic_ns(),"response":response}
        with ThreadPoolExecutor(max_workers=2) as pool:
            observed=list(pool.map(take,["P1-COUPON-A","P1-COUPON-B"]))
        self.save("racing-coupon-locks.json",observed)
        assert max(x["started_ns"] for x in observed)<min(x["ended_ns"] for x in observed)
        winner,=[x["order"] for x in observed if x["response"]["status"] == 200]
        loser,=[x["order"] for x in observed if (x["response"]["status"],x["response"]["body"]["code"]) ==
                (409,"BENEFIT_NOT_ELIGIBLE")]
        locked=self.data("winner-lock-retry",self.lock(winner,number))
        assert locked["payableAmount"] == 40 and locked["couponDiscount"] == 10 and locked["status"]=="LOCKED"
        self.save("locked-coupon.json",self.benefit(number))
        assert self.benefit(number)["lock"]==winner
        assert self.sql(f"SELECT COUNT(*) FROM b1_marketing.pricing_lock WHERE order_no='{loser}';")=="0"
        self.stop(self.apps["marketing"])

        self.stage="cancel-coupon-order-through-real-trade"
        self.service("inventory", {f"ecommerce.inventory.{part}.enabled":"false" for part in
                  ("outbox","order-consumer","return-consumer","reconciliation")})
        self.sql(f"""
INSERT INTO b1_inventory.warehouse(id,code,name,status,version,created_at,updated_at)
VALUES({WAREHOUSE},'P1-COUPON','Synthetic fixture','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_inventory.inventory_balance(id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES(1,{WAREHOUSE},{SKU},10,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
        reserve=self.data("coupon-order-inventory",self.get("inventory","/api/v1/inventory/internal/reservations",{
            "reservationNo":"RSV-"+winner,"orderNo":winner,"warehouseId":WAREHOUSE,
            "expiresAt":(now+timedelta(minutes=30)).isoformat(),"items":[{"skuId":SKU,"quantity":1}]},base.TRADE_HEADER))
        assert reserve["status"]=="RESERVED"
        self.trade("trade-coupon-cancel",**{"ecommerce.trade.client.inventory-base-url":"http://inventory:18103"})
        self.host_check("host-coupon-cancel.json")
        self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,discount_amount,payment_deadline,created_at,updated_at)
VALUES(1,'{winner}',91001,'{winner}',REPEAT('0',64),'RSV-{winner}','P1-COUPON',{WAREHOUSE},'PENDING_PAYMENT',
 40.00,50.00,10.00,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 15 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_item
(id,order_id,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,discount_amount,payable_amount,created_at)
VALUES(1,1,843103,{SKU},'Coupon Fixture','COUPON-FIXTURE','Coupon SKU','{{}}',50,1,1,50,10,40,UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_address_snapshot
(id,order_id,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,created_at)
VALUES(1,1,843104,'Fixture Receiver','00000000000','Fixture','Fixture','Fixture','Fixture','000000',UTC_TIMESTAMP(3));
""")
        for index in range(2):
            answer=self.data(f"coupon-trade-cancel-{index}",self.cancel(winner))
            assert answer["status"]=="CANCELED"
        self.save("cancel-before-marketing-event.json", self.event_facts(winner))
        self.stop(self.apps["trade"])
        self.stop(self.apps["inventory"])
        self.stage="publish-real-cancellation"
        self.start_broker(self.broker_image)
        for topic in ["ecommerce-order-events","ecommerce-flash-sale-events"]:
            answer=self.admin("updateTopic","-c","EcommerceCluster","-t",topic,"-r","4","-w","4")
            assert "success" in answer.lower()
        self.trade("trade-coupon-events",**{"ecommerce.trade.outbox.enabled":"true"})
        self.host_check("host-coupon-publish.json")
        self.wait_for(lambda:self.event_facts(winner)["cancellations"]==1,85,"real Trade cancellation published")
        self.stop(self.apps["trade"])
        self.stage="reuse-coupon-after-real-release"
        self.marketing("marketing-lifecycle",True)
        self.host_check("host-coupon-consumer.json")
        self.wait_for(lambda:self.event_facts(winner)["receipts"]==1 and self.benefit(number)["status"]=="AVAILABLE",
                      80,"Marketing consumes cancellation and makes the benefit available")
        facts=self.event_facts(winner)
        released=self.benefit(number)
        self.save("coupon-released-ledgers.json",{"facts":facts,"benefit":released})
        assert facts=={"trade":"CANCELED","inventory":"RELEASED","cancellations":1,"receipts":1,"releases":1,"lockState":"RELEASED"}
        assert not released["lock"] and not released["lockedAt"] and not released["redeem"], "AVAILABLE coupon retains old lock metadata"
        next_order="P1-COUPON-REUSE"
        relock=self.data("coupon-reused-new-order",self.lock(next_order,number))
        assert relock["status"]=="LOCKED" and relock["payableAmount"]==40
        view=self.data("old-order-replay-after-reuse",self.lock(winner,number))
        assert view["status"]=="RELEASED" and self.benefit(number)["lock"]==next_order
        for index in range(2):
            response=self.data(f"second-order-redeem-{index}",self.get("marketing",
                API+f"/internal/pricing-locks/orders/{next_order}/redeem",{},base.TRADE_HEADER))
            assert response["status"]=="REDEEMED"
        blocked=self.lock("P1-COUPON-AFTER-REDEEM",number)
        self.save("redeemed-coupon-reuse-rejected.json",blocked)
        assert (blocked["status"],blocked["body"]["code"])==(409,"BENEFIT_NOT_ELIGIBLE")
        terminal=self.benefit(number)
        rows=self.sql("SELECT CONCAT(order_no,'|',status) FROM b1_marketing.pricing_lock WHERE order_no LIKE 'P1-COUPON-%' ORDER BY order_no;").splitlines()
        self.save("final-coupon-ledgers.json",{"benefit":terminal,"locks":rows,"cancellation":self.event_facts(winner)})
        assert terminal["status"]=="REDEEMED" and terminal["redeem"]==next_order and terminal["lock"] is None
        assert sorted(rows)==sorted([winner+"|RELEASED",next_order+"|REDEEMED"])
        self.result.update(status="PASS_SCOPED", cases=["P1-02-order-cancel-and-coupon-reuse"],
                           scope="real Marketing, Trade, Inventory, MySQL, and RocketMQ; seeded winning checkout and synthetic API callers")


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--evidence",required=True)
    parser.add_argument("--docker",default=shutil.which("docker"))
    parser.add_argument("--pwsh",default=shutil.which("pwsh"))
    run=CouponCase(parser.parse_args())
    try:
        run.execute()
    except Exception as exc:
        run.result.update(stage=run.stage,error=repr(exc))
    finally:
        run.cleanup()
        run.save("result.json",run.result)
        run.save("sha256.json",{item.name:hashlib.sha256(item.read_bytes()).hexdigest()
                                for item in run.directory.iterdir() if item.is_file() and item.name!="sha256.json"})
    print(json.dumps(run.result,ensure_ascii=False))
    return 0 if run.result["status"]=="PASS_SCOPED" and run.result["cleanup"]=="PASS" else 1


if __name__=="__main__":
    raise SystemExit(main())
