"""One payment request during a short pause of disposable MySQL, followed by reconciliation."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("p0_payment", HERE / "verify-p0-payment-recovery.py")
payment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payment)
PROBE_REQUEST = """
const fs = require('node:fs');
const req = JSON.parse(fs.readFileSync(0, 'utf8'));
(async () => {
  const began = process.hrtime.bigint();
  try {
    const response = await fetch(req.url, {method:'POST', headers:req.headers,
      body:JSON.stringify(req.body), signal:AbortSignal.timeout(4500)});
    console.log(JSON.stringify({status:response.status, body:await response.json(),
      elapsedMs:Number(process.hrtime.bigint()-began)/1e6}));
  } catch (err) {
    console.log(JSON.stringify({transport:err.name, elapsedMs:Number(process.hrtime.bigint()-began)/1e6}));
  }
})();
"""


class DatabaseRecovery(payment.PaymentCases):
    def execute(self):
        super().execute()
        self.result.update(status="INCONCLUSIVE", prerequisite_cases=self.result["cases"], cases=[])
        self.host_check("host-before-db-interruption.json")
        self.stage = "owned-database-short-pause"
        order = "BOOT412-P1-05-DB-PAUSE"
        key = "P1-05-DB-PAUSE-IDENTITY"
        self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES (95,'{order}',91001,'{order}',REPEAT('0',64),'RSV-{order}','P1-FIXTURE',841002,
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
""")
        owner = self.docker("inspect", "--format", '{{index .Config.Labels "codex.task"}}', self.mysql)
        assert owner == self.ident
        before = self.facts(order)
        assert before["payments"] == before["transactions"] == before["refunds"] == 0
        request = {"url":"http://payment:18105"+payment.PAYMENT_PATH,
                   "headers":{"Content-Type":"application/json", **self.headers(key)},
                   "body":{"orderNo":order,"channel":"MOCK"}}
        self.docker("pause", self.mysql)
        try:
            assert self.docker("inspect", "--format", "{{.State.Paused}}", self.mysql) == "true"
            response = json.loads(self.docker("exec", "-i", self.http, "node", "-e", PROBE_REQUEST,
                                               stdin=json.dumps(request), timeout=12))
            assert self.docker("inspect", "--format", "{{.State.Paused}}", self.mysql) == "true"
            self.save("paused-database-request.json", response)
            if "transport" in response:
                assert response["transport"] == "TimeoutError" and 4000 <= response["elapsedMs"] <= 8500
            else:
                assert 500 <= response["status"] <= 599
        finally:
            assert self.docker("inspect", "--format", '{{index .Config.Labels "codex.task"}}', self.mysql) == self.ident
            self.docker("unpause", self.mysql)
        assert self.docker("inspect", "--format", "{{.State.Paused}}", self.mysql) == "false"
        self.wait_for(lambda:self.sql("SELECT 1")=="1",25,"disposable MySQL recovery")
        self.stage = "reconcile-and-replay-stable-identity"
        observed = self.get("payment", payment.PAYMENT_PATH+"/by-idempotency-key/"+key,headers=self.customer)
        self.save("readback-before-retry.json", observed)
        assert observed["status"] in (200,404)
        if observed["status"] == 200:
            assert observed["body"]["data"]["status"] == "PROCESSING"
        settled_intent = self.create(order,key,"same-key-recovered")
        assert settled_intent["status"] == "PROCESSING" and settled_intent["amount"] == 39.8
        if observed["status"] == 200:
            assert observed["body"]["data"]["paymentNo"] == settled_intent["paymentNo"]
        self.callback(order,settled_intent["paymentNo"],2)
        self.assert_settled(order,settled_intent["paymentNo"])
        self.host_check("host-after-db-recovery.json")
        self.result.update(status="PASS_SCOPED", cases=["P1-05-database-disruption-and-idempotent-recovery"],
                           cutoff="one client attempt while the owned MySQL process was paused",
                           scope="actual Trade, Payment and MySQL; no settlement while the database was paused")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence",required=True)
    parser.add_argument("--docker",default=shutil.which("docker"))
    parser.add_argument("--pwsh",default=shutil.which("pwsh"))
    runner=DatabaseRecovery(parser.parse_args())
    try:
        runner.execute()
    except Exception as exc:
        runner.result.update(status="INCONCLUSIVE", stage=runner.stage, error=repr(exc))
    finally:
        runner.cleanup()
        runner.save("result.json",runner.result)
    print(json.dumps(runner.result))
    return 0 if runner.result["status"]=="PASS_SCOPED" and runner.result["cleanup"]=="PASS" else 1


if __name__=="__main__":
    raise SystemExit(main())