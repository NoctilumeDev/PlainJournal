"""One prepared order across three customer sessions in the isolated Boot 4 lab."""
import argparse
import base64
import hashlib
import hmac
import importlib.util
import json
import os
import shutil
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location(
    "staged_shipment", HERE / "verify-p0-11-12-shipment-staged-settlement.py"
)
shipment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shipment)
refund = shipment.refund
cancel = refund.cancel
base = refund.base
SOURCE = json.loads((INPUTS / "coordinates.json").read_text())["candidate"]
ORIGINAL = json.loads((INPUTS / "coordinates.json").read_text())["baseline"]
CONTROL = "BOOT412-P0-05-CONTROL"
RACE = "BOOT412-P0-05-RACE"
PAYMENT_PATH = cancel.p0.PAYMENT_PATH
RACE_CALLS = """
const fs = require("node:fs");
const requests = JSON.parse(fs.readFileSync(0, "utf8"));
const clock = () => Number(process.hrtime.bigint() / 1000n);
async function perform(request) {
    const {name, session, url, headers, body} = request;
    const startedUs = clock();
    const options = {headers, signal: AbortSignal.timeout(9000)};
    if (body !== null) {
        options.method = "POST";
        options.body = JSON.stringify(body);
    }
    const res = await fetch(url, options);
    const bodyJson = await res.json();
    return {name, session, startedUs, finishedUs: clock(), status: res.status, body: bodyJson};
}
Promise.all(requests.map(perform)).then(results => console.log(JSON.stringify(results)))
    .catch(error => { console.error(error.message, error.cause?.code); process.exitCode = 1; });
"""


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mint_test_session(number):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")

    now = int(time.time())
    payload = b".".join((encode({"alg": "HS256", "typ": "JWT"}), encode({
        "sub": "91001", "iss": "ecommerce-identity", "roles": ["CUSTOMER"],
        "iat": now, "exp": now + 1800, "jti": f"p0-05-fixture-{number}",
    })))
    signature = base64.urlsafe_b64encode(
        hmac.new(base.runtime.TOKEN_SECRET.encode(), payload, hashlib.sha256).digest()
    ).rstrip(b"=")
    return (payload + b"." + signature).decode()


class Multisession(shipment.ShipmentCases):
    def request(self, app, path, session, label, *, body=None, key=None):
        headers = {"Authorization": "Bearer " + self.sessions[session], "Connection": "close"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = key
        return {"name": label, "session": session, "url": f"http://{app}:{base.PORTS[app]}{path}",
                "headers": headers, "body": body}

    def batch(self, requests, label):
        calls = json.loads(self.docker("exec", "-i", self.http, "node", "-e", RACE_CALLS,
                                      stdin=json.dumps(requests), timeout=15))
        assert len(calls) == len(requests) and len({call["name"] for call in calls}) == len(requests)
        overlap = min(row["finishedUs"] for row in calls) - max(row["startedUs"] for row in calls)
        self.save(label + ".json", {"overlap_us": overlap, "calls": calls})
        assert overlap > 0, "All request windows must share a client-side intersection"
        return calls

    def cancel(self, order):
        if order != RACE:
            return super().cancel(order)
        self.stage = "three-customer-sessions-concurrent-payment-cancel-read"
        payment = self.apps["payment"]
        self.docker("start", payment)
        self.ready("payment")
        self.host_check("host-before-session-overlap.json")
        original = self.facts(order)
        assert original["paymentState"] == "PROCESSING" and original["transactions"] == 0, original
        payload = {"orderNo": order, "channel": "MOCK"}
        order_path = "/api/v1/trade/orders/" + order
        read_pay = PAYMENT_PATH + "/by-order/" + order
        calls = self.batch([
            self.request("payment", PAYMENT_PATH, 0, "pay-original-key", body=payload, key="IDEM-" + order),
            self.request("trade", order_path + "/cancel", 1, "cancel", body={}),
            self.request("trade", order_path, 2, "order-query"),
            self.request("payment", PAYMENT_PATH, 2, "pay-different-key", body=payload, key="P0-05-NEW-KEY"),
            self.request("trade", order_path + "/cancel", 0, "cancel-replay", body={}),
            self.request("payment", read_pay, 1, "payment-query"),
        ], "customer-session-overlap")
        self.host_check("host-after-session-overlap.json")
        self.stop(payment)
        accepted = []
        for call in calls:
            data = call["body"].get("data") or {}
            if call["name"].startswith("cancel"):
                if call["status"] == 200:
                    assert data["status"] in {"CANCELING", "CANCELED"}, call
                    if data["status"] == "CANCELED":
                        accepted.append(call)
                else:
                    assert (call["status"], call["body"]["code"]) == (409, "INVALID_STATE"), call
            elif call["name"].startswith("pay"):
                assert call["status"] == 200 and data["paymentNo"] == original["paymentNo"] \
                    and data["status"] == "PROCESSING", call
            else:
                assert call["status"] == 200 and data["status"] in {"PENDING_PAYMENT", "CANCELING", "CANCELED"}, call
        assert accepted, calls
        after = self.facts(order)
        assert after["payments"] == 1 and after["transactions"] == after["refunds"] == 0, after
        # The inherited runner observes this successful cancellation before settlement.
        self.stage = "cancel-before-channel-settlement"
        return accepted[0]

    def execute(self):
        repo = str(base.REPO)
        clean = self.command("git", "-C", repo, "status", "--porcelain")
        head = self.command("git", "-C", repo, "rev-parse", "HEAD")
        assert clean == "" and head == SOURCE, "Use the clean Boot 4 coupon candidate"
        diff = self.command("git", "-C", repo, "diff", "--name-only", ORIGINAL, SOURCE).splitlines()
        assert diff and all(name.startswith("backend/services/marketing-service/") for name in diff), diff

        self.sessions = [mint_test_session(session) for session in range(3)]
        signatures = [hashlib.sha256(token.encode()).hexdigest() for token in self.sessions]
        assert len(set(signatures)) == 3
        self.save("fixture-identity.json", {
            "subject": 91001, "roles": ["CUSTOMER"], "token_digests": signatures,
            "variant": "three synthesized customer JWTs accepted by the app; shared isolated network",
            "comparison_base": ORIGINAL, "artifact_source": SOURCE, "candidate": SOURCE, "diff": diff,
            "driver_sha256": sha(Path(__file__)),
            "reused_runners": {Path(runner.__file__).name: sha(Path(runner.__file__))
                               for runner in (shipment, refund, cancel, cancel.p0, base, base.runtime)},
        })
        self.save("candidate-jars.json", json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig")))
        self.save("coordinates.json", {"candidate": SOURCE})
        # Bind the inherited runner to the freshly built case artifacts.
        cancel.INPUTS = self.directory
        cancel.ORDERS = (CONTROL, RACE)
        cancel.SKU_BY_ORDER = {CONTROL: 842001, RACE: 842002}
        refund.RefundCase.execute(self)
        self.result.update(status="INCONCLUSIVE", retained_phases=self.result["cases"], cases=[])
        self.stage = "three-customer-sessions-verify-terminal-books"
        before = self.refund_state(RACE)
        payment_no = before["payment"]["paymentNo"]
        refund_no = before["refund"]["no"]
        requests = []
        for session in range(3):
            requests.extend((
                self.request("trade", f"/api/v1/trade/orders/{RACE}", session, f"order-{session}"),
                self.request("payment", f"{PAYMENT_PATH}/by-order/{RACE}", session, f"payment-{session}"),
                self.request("payment", f"/api/v1/payment/refunds/{refund_no}", session, f"refund-{session}"),
            ))
        calls = self.batch(requests, "terminal-across-sessions")
        expected = {"order": ("status", "CLOSED"), "payment": ("paymentNo", payment_no),
                    "refund": ("refundNo", refund_no)}
        for call in calls:
            path, value = expected[call["name"].split("-")[0]]
            assert call["status"] == 200 and call["body"]["data"][path] == value, call
            if path != "status":
                assert call["body"]["data"]["status"] == "SUCCESS", call
        after = self.refund_state(RACE)
        assert before == after, after
        release_count = int(self.sql(
            f"SELECT COUNT(*) FROM b1_inventory.stock_movement WHERE reservation_no='RSV-{RACE}' AND movement_type='RELEASE'"
        ))
        cancel_events = int(self.sql(
            f"SELECT COUNT(*) FROM b1_trade.outbox_event WHERE aggregate_id='{RACE}' AND event_type='OrderCanceled'"
        ))
        final = {"order": RACE, "ledgers": after, "release_movements": release_count,
                 "cancel_events": cancel_events, "sessions": len(self.sessions)}
        self.save("multisession-final-ledgers.json", final)
        assert release_count == cancel_events == 1, final
        assert after["payment"]["transactions"] == after["refund"]["transactions"] == 1, final
        self.host_check("host-after-terminal-readback.json")
        self.result.update(status="PASS_SCOPED", source=SOURCE, cases=["P0-05-same-account-payment-cancel-query"],
                           scope="three isolated auth fixtures; client request overlap; real MySQL, Trade, Inventory, Payment and RocketMQ; mock channel; prepared order")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = Multisession(parser.parse_args())
    try:
        run.execute()
    except Exception as exc:
        run.result.update(status="INCONCLUSIVE", stage=run.stage, error=repr(exc))
    finally:
        run.cleanup()
        run.save("result.json", run.result)
        run.save("sha256.json", {path.name: sha(path) for path in run.directory.iterdir()
                                if path.is_file() and path.name != "sha256.json"})
    print(json.dumps(run.result, ensure_ascii=False))
    return 0 if run.result["status"] == "PASS_SCOPED" and run.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
