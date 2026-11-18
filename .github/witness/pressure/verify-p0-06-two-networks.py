import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shutil
import threading
import time

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location("payment_cases", HERE / "verify-p0-payment-recovery.py")
payment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payment)
base = payment.base
ORDER = "BOOT412-P0-06"


class NetworkCase(payment.PaymentCases):
    def __init__(self, args):
        super().__init__(args)
        self.other_network = self.ident + "-secondary"
        self.other_created = False

    def wire(self, node, path, body, headers):
        request = {"url": "http://payment:18105" + path, "body": body, "headers": headers}
        return json.loads(self.docker("exec", "-i", node, "node", "-e", base.NODE_REQUEST,
                                      stdin=json.dumps(request), timeout=18))

    def batch(self, name, nodes, operation):
        lock = threading.Lock()
        active = peak = 0
        started = time.monotonic_ns()

        def one(index):
            nonlocal active, peak
            cohort = index % len(nodes)
            begin = time.monotonic_ns()
            with lock:
                active += 1
                peak = max(peak, active)
            try:
                response = operation(index, nodes[cohort])
                return {"request": index, "network": cohort, "started_ns": begin,
                        "finished_ns": time.monotonic_ns(), "response": response}
            finally:
                with lock:
                    active -= 1

        with ThreadPoolExecutor(max_workers=4) as pool:
            calls = list(pool.map(one, range(24)))
        self.save(name + ".json", {"elapsed_ms": (time.monotonic_ns() - started) // 1_000_000,
                                   "parallelism": 4, "peak_observed_in_flight": peak, "total": len(calls), "calls": calls})
        assert peak >= 2 and {call["network"] for call in calls} == {0, 1}
        return [call["response"] for call in calls]

    def cleanup(self):
        super().cleanup()
        if self.other_created:
            self.docker("network", "rm", self.other_network, check=False)
            residue = self.docker("network", "ls", "--filter", "name=" + self.other_network, "--format", "{{.Name}}")
            self.save("secondary-cleanup.json", {"remainingNetworks": residue})
            if residue:
                self.result["cleanup"] = "FAIL"

    def execute(self):
        super().execute()
        self.result.update(status="INCONCLUSIVE", prerequisite_cases=self.result["cases"], cases=[])
        self.stage = "fixed-candidate-two-policy-owned-networks"
        self.save("network-test-identity.json",
                  {"runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   "base_runner_sha256": hashlib.sha256(Path(payment.__file__).read_bytes()).hexdigest()})
        self.sql(f"""
INSERT INTO b1_trade.trade_order
(id,order_no,user_id,idempotency_key,request_hash,reservation_no,warehouse_code,warehouse_id,status,
 total_amount,original_amount,payment_deadline,created_at,updated_at)
VALUES (3,'{ORDER}',91001,'{ORDER}',REPEAT('0',64),'RSV-{ORDER}','P0-FIXTURE',841002,
 'PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
INSERT INTO b1_trade.order_item
(id,order_id,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,payable_amount,created_at)
SELECT 3,3,product_id,sku_id,product_title,sku_code,sku_name,spec_json,unit_price,quantity,
 line_no,line_amount,payable_amount,UTC_TIMESTAMP(3) FROM b1_trade.order_item WHERE id=1;
INSERT INTO b1_trade.order_address_snapshot
(id,order_id,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,created_at)
SELECT 3,3,source_address_id,recipient_name,phone,province,city,district,detail_address,postal_code,UTC_TIMESTAMP(3)
FROM b1_trade.order_address_snapshot WHERE id=1;
""")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.other_network)
        self.other_created = True
        self.docker("network", "connect", "--alias", "payment", self.other_network, self.apps["payment"])
        image = self.image(os.environ["PJ_NODE_REF"])
        other = self.docker("run", "-d", "--pull=never", "--label", "codex.task=" + self.ident,
                            "--name", self.ident + "-http-secondary", "--network", self.other_network,
                            "--memory", "96m", "--cpus", "1", image, "sleep", "3600")
        self.containers.append(other)
        nodes = (self.http, other)
        client_links = [json.loads(self.docker("inspect", "--format", "{{json .NetworkSettings.Networks}}", node))
                        for node in nodes]
        payment_links = json.loads(
            self.docker("inspect", "--format", "{{json .NetworkSettings.Networks}}", self.apps["payment"]))
        assert list(client_links[0]) == [self.ident] and list(client_links[1]) == [self.other_network]
        assert client_links[0][self.ident]["IPAddress"] != client_links[1][self.other_network]["IPAddress"]
        assert set(payment_links) == {self.ident, self.other_network}
        self.save("client-network-readback.json", {"clients": client_links, "payment": payment_links})
        self.host_check("host-before-concurrent-requests.json")

        self.stage = "cross-network-concurrent-create"
        created = self.batch("cross-network-create", nodes, lambda i, node: self.wire(
            node, payment.PAYMENT_PATH, {"orderNo": ORDER, "channel": "MOCK"}, self.headers(f"NET-P0-06-KEY-{i % 3}")))
        assert all(response["status"] == 200 for response in created)
        intent_ids = {response["body"]["data"]["paymentNo"] for response in created}
        assert len(intent_ids) == 1
        payment_no = intent_ids.pop()
        assert self.facts(ORDER)["payments"] == 1

        self.stage = "cross-network-concurrent-channel-callback"
        stamp = int(time.time())
        event = "P0-06-MOCK-GATEWAY-EVT"
        transaction = "P0-06-MOCK-GATEWAY-TXN"
        message = "|".join((payment_no, event, transaction, "SUCCESS", "39.8", str(stamp)))
        signed = hmac.new(base.runtime.INTERNAL_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
        callback = {"paymentNo": payment_no, "externalEventId": event, "externalTransactionNo": transaction,
                    "status": "SUCCESS", "amount": 39.8, "timestamp": stamp, "signature": signed}
        callbacks = self.batch("cross-network-callback", nodes, lambda _, node: self.wire(
            node, "/api/v1/payment/callbacks/mock", callback, {}))
        assert all(response["status"] == 200 and response["body"]["data"]["paymentNo"] == payment_no
                   and response["body"]["data"]["status"] == "SUCCESS" for response in callbacks)
        for index, node in enumerate(nodes):
            observed = self.data(f"lookup-from-network-{index}",
                self.wire(node, payment.PAYMENT_PATH + "/by-order/" + ORDER, None, self.customer))
            assert observed["paymentNo"] == payment_no and observed["status"] == "SUCCESS"
        self.assert_settled(ORDER, payment_no)
        self.host_check("host-after-concurrent-requests.json")
        self.result.update(status="PASS_SCOPED", cases=["P0-06-cross-network-payment-idempotency"],
                           scope="two distinct isolated networks; same account; 24 intent and 24 callback requests; retained payment Outbox")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = NetworkCase(parser.parse_args())
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
