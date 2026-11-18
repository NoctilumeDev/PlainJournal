import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import shutil
import threading
import time

HERE = Path(__file__).resolve().parent
INPUTS = Path(os.environ['PJ_WITNESS_INPUTS'])
spec = importlib.util.spec_from_file_location("runtime", HERE / "verify-boot412-payment-inventory.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class LastStock(base.Chain):
    def container(self, suffix, image, options, command):
        if suffix == "inventory":
            command = ["-Xmx224m" if part == "-Xmx288m" else part for part in command]
        return super().container(suffix, image, options, command)

    def concurrent(self, name, operations, action):
        active = peak = 0
        lock = threading.Lock()
        start = time.monotonic_ns()

        def call(item):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
            begin = time.monotonic_ns()
            try:
                response = action(item)
                return {"request": item, "start_ns": begin, "end_ns": time.monotonic_ns(),
                        "response": response}
            finally:
                with lock:
                    active -= 1

        with ThreadPoolExecutor(max_workers=4) as pool:
            observed = list(pool.map(call, operations))
        self.save(name + ".json", {"count": len(observed), "peak": peak,
                                   "elapsed_ms": (time.monotonic_ns() - start) // 1_000_000, "observed": observed})
        assert peak == 4
        return observed

    def round(self, n):
        sku = 842900 + n
        warehouse = 842990
        self.sql(f"""
INSERT INTO b1_inventory.inventory_balance(id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES({n}, {warehouse}, {sku}, 1, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
""")
        expiry = (datetime.now(timezone.utc) + timedelta(minutes=20)).isoformat()
        sequence = list(range(16)) + list(range(8))
        random.Random(78000 + n).shuffle(sequence)
        ids = [f"P1-LAST-{n}-{i:02}" for i in range(16)]
        prefix = "/api/v1/inventory/internal/reservations"

        def reserve(i):
            return self.get("inventory", prefix, {"reservationNo": ids[i], "orderNo": "ORDER-" + ids[i],
                "warehouseId": warehouse, "expiresAt": expiry, "items": [{"skuId": sku, "quantity": 1}]}, base.TRADE_HEADER)

        responses = self.concurrent(f"stock-{n}-reservations", sequence, reserve)
        states = {}
        for item in responses:
            response = item["response"]
            assert response["status"] == 200 and response["body"]["code"] == "OK"
            data = response["body"]["data"]
            assert data["reservationNo"] == ids[item["request"]]
            states.setdefault(data["reservationNo"], set()).add(data["status"])
        assert len(states) == 16 and all(len(values) == 1 for values in states.values())
        winners = [name for name, state in states.items() if state == {"RESERVED"}]
        losers = [name for name, state in states.items() if state == {"REJECTED"}]
        assert len(winners) == 1 and len(losers) == 15
        winner, loser = winners[0], losers[0]
        before = self.sql(f"""
SELECT CONCAT(on_hand,'|',reserved) FROM b1_inventory.inventory_balance WHERE sku_id={sku};
SELECT CONCAT(status,'|',COUNT(*))
FROM b1_inventory.inventory_reservation WHERE reservation_no LIKE 'P1-LAST-{n}-%'
GROUP BY status ORDER BY status;
""").splitlines()
        self.save(f"stock-{n}-before-confirm.json", before)
        assert before == ["1|1", "REJECTED|15", "RESERVED|1"]

        def confirm(identifier):
            return self.get("inventory", prefix + "/" + identifier + "/confirm", {}, base.TRADE_HEADER)
        read = self.concurrent(f"stock-{n}-confirm-replay", [winner] * 8, confirm)
        assert all(row["response"]["status"] == 200 and row["response"]["body"]["data"]["status"] == "CONFIRMED"
                   for row in read)
        denied = confirm(loser)
        self.save(f"stock-{n}-loser-confirm.json", denied)
        assert denied["status"] == 200 and denied["body"]["code"] == "OK"
        assert denied["body"]["data"]["status"] == "REJECTED"

        final = json.loads(self.sql(f"""
SELECT JSON_OBJECT(
 'onHand',(SELECT on_hand FROM b1_inventory.inventory_balance WHERE sku_id={sku}),
 'reserved',(SELECT reserved FROM b1_inventory.inventory_balance WHERE sku_id={sku}),
 'movement',(SELECT COUNT(*) FROM b1_inventory.stock_movement WHERE sku_id={sku} AND movement_type='CONFIRM'),
 'confirmed',(SELECT COUNT(*) FROM b1_inventory.inventory_reservation
              WHERE reservation_no LIKE 'P1-LAST-{n}-%' AND status='CONFIRMED'),
 'rejected',(SELECT COUNT(*) FROM b1_inventory.inventory_reservation
             WHERE reservation_no LIKE 'P1-LAST-{n}-%' AND status='REJECTED'),
 'confirmedEvents',(SELECT COUNT(*) FROM b1_inventory.outbox_event
                    WHERE aggregate_id='{winner}' AND event_type='InventoryReservationConfirmed'),
 'loserConfirmedEvents',(SELECT COUNT(*) FROM b1_inventory.outbox_event
                    WHERE aggregate_id='{loser}' AND event_type='InventoryReservationConfirmed'),

 'quantity',(SELECT SUM(-quantity_delta) FROM b1_inventory.stock_movement WHERE sku_id={sku} AND movement_type='CONFIRM')
 );
"""))
        self.save(f"stock-{n}-final-ledgers.json", final)
        assert final == {"onHand": 0, "reserved": 0, "movement": 1, "confirmed": 1, "rejected": 15, "quantity": 1, "confirmedEvents": 1, "loserConfirmedEvents": 0}

    def execute(self):
        self.stage = "fixed-coordinates"
        src = json.loads((INPUTS / "coordinates.json").read_text(encoding="utf-8-sig"))["candidate"]
        sha = self.command("git", "-C", str(base.REPO), "rev-parse", "HEAD")
        assert sha == src and not self.command("git", "-C", str(base.REPO), "status", "--porcelain")
        self.jars = json.loads((INPUTS / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        self.java = self.image(os.environ["PJ_JRE_REF"])
        mysql_image = self.image(os.environ["PJ_MYSQL_REF"])
        node_image = self.image(os.environ["PJ_NODE_REF"])
        self.save("coordinates.json", {"source": sha, "images": [self.java, mysql_image, node_image],
            "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "series_seeds": [78001, 78002, 78003]})
        self.host_check("host-before.json")
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.stage = "owned-inventory-and-mysql"
        self.mysql = self.container("mysql", mysql_image, [
            "--network-alias", "mysql", "--memory", "650m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=560m", "-e", "MYSQL_ROOT_PASSWORD=" + base.runtime.DB_SECRET,
            "-e", "MYSQL_ROOT_HOST=%", "-e", "TZ=UTC"
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.save("mysql-version.json", {"version": self.sql("SELECT VERSION()")})
        self.sql("CREATE DATABASE b1_inventory;")
        self.http = self.container("http", node_image, ["--memory", "96m", "--cpus", "1"], ["sleep", "3600"])
        self.service("inventory", {f"ecommerce.inventory.{feature}.enabled": "false"
                                    for feature in ("outbox", "order-consumer", "return-consumer", "reconciliation")})
        self.sql("""
INSERT INTO b1_inventory.warehouse(id,code,name,status,version,created_at,updated_at)
VALUES(842990, 'P1-LAST-STOCK', 'Isolated fixture', 'ACTIVE', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
""")
        for n in range(1, 4):
            self.stage = f"stock-race-{n}"
            self.host_check(f"host-race-{n}.json")
            self.round(n)
        self.host_check("host-final.json")
        self.result.update(status="PASS_SCOPED", source=sha, cases=["P1-01-inventory-last-unit"],
                           scope="last-stock race through actual Inventory and MySQL; orders are synthetic keys, no Payment client")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = LastStock(parser.parse_args())
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
