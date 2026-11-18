"""Start fixed product artifacts in a disposable local browser fixture."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
PRODUCT = Path(os.environ["PJ_SOURCE"])
spec = importlib.util.spec_from_file_location("repricing", Path(os.environ["PJ_REPRICING_SCRIPT"]))
price = importlib.util.module_from_spec(spec)
spec.loader.exec_module(price)
base = price.base

GATEWAY_PORT = 18681
PORTS = {"storefront-web": 18300, "admin-web": 18301}
GATEWAY_PATH = "backend/ecommerce-gateway/target/ecommerce-gateway.jar"


class BrowserLab(price.PriceCase):
    def __init__(self, args):
        super().__init__(args)
        self.previews = {}

    def host_check(self, name):
        if os.name != "nt":
            return super().host_check(name)
        script = r"""
$os = Get-CimInstance Win32_OperatingSystem
$ports = @{}
foreach ($protocol in 'tcp','udp') {
    $raw = netsh int ipv4 show dynamicport $protocol
    $numbers = [regex]::Matches(($raw -join "`n"), ':\s*(\d+)') | ForEach-Object { [int]$_.Groups[1].Value }
    if (@($numbers).Count -ne 2) { throw "Missing IPv4 ephemeral port range" }
    $start = $numbers[0]
    $limit = $start + $numbers[1]
    $used = if ($protocol -eq 'tcp') { Get-NetTCPConnection -ErrorAction SilentlyContinue }
            else { Get-NetUDPEndpoint -ErrorAction SilentlyContinue }
    $unique = @($used | Where-Object {
        $_.LocalAddress -notlike '*:*' -and $_.LocalPort -ge $start -and $_.LocalPort -lt $limit
    } | Select-Object -ExpandProperty LocalPort -Unique)
    $ports[$protocol] = $unique.Count / $numbers[1]
}
@{ availableGiB=$os.FreePhysicalMemory/1MB; totalGiB=$os.TotalVisibleMemorySize/1MB;
   ipv4DynamicPortUse=$ports } | ConvertTo-Json -Compress
"""
        snapshot = json.loads(self.command(self.args.pwsh, "-NoProfile", "-NonInteractive", "-Command", script))
        own_usage = 1 - snapshot["availableGiB"] / snapshot["totalGiB"]
        engine = self.docker("info", "--format", "{{.OSType}}")
        snapshot.update(minimumAvailableGiB=3, maximumMemoryUsedFraction=0.82, memoryUsedFraction=own_usage,
                        dockerType=engine)
        snapshot["passed"] = (snapshot["availableGiB"] >= 3 and own_usage < 0.82
                              and max(snapshot["ipv4DynamicPortUse"].values()) < 0.8 and engine == "linux")
        self.save(name, snapshot)
        if not snapshot["passed"]:
            raise RuntimeError("browser fixture host resource preflight")

    def service(self, app, overrides, fixture_name=None):
        super().service(app, overrides, fixture_name)
        self.host_check(f"host-{fixture_name or app}-ready.json")

    def gateway(self):
        jar = PRODUCT / GATEWAY_PATH
        digest = self.jars[GATEWAY_PATH]
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        discovered = {"identity": 18101, "catalog": 18102, "inventory": 18103, "trade": 18104, "payment": 18105,
                      "marketing": 18107}
        instances = [f"--spring.cloud.discovery.client.simple.instances.{app}-service[0].uri=http://{app}:{port}"
                     for app, port in discovered.items()]
        binding = ["-p", f"127.0.0.1:{GATEWAY_PORT}:18000"] if os.name == "nt" else []
        self.gateway_id = self.container("gateway", self.java, [
            "--network-alias", "gateway", "--memory", "570m", "--cpus", "1.5",
            *binding,
            "--mount", f"type=bind,source={jar},target=/app.jar,readonly",
            "-e", "IDENTITY_JWT_SECRET=" + base.runtime.TOKEN_SECRET,
            "-e", "REDIS_HOST=redis", "-e", "REDIS_PORT=6379",
        ], ["java", "-Xms48m", "-Xmx288m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-jar", "/app.jar",
            "--spring.config.import=", "--spring.cloud.nacos.config.enabled=false",
            "--spring.cloud.nacos.discovery.enabled=false", *instances])
        if os.name == "nt":
            self.gateway_url = f"http://127.0.0.1:{GATEWAY_PORT}"
        else:
            details = json.loads(self.docker("inspect", self.gateway_id))[0]
            address = details["NetworkSettings"]["Networks"][self.ident]["IPAddress"]
            self.gateway_url = f"http://{address}:18000"
        auth_health = self.gateway_url + "/actuator/health"
        self.wait_for(lambda: self.fetch(auth_health)["status"] == "UP", 100, "owned gateway health")
        catalog_url = self.gateway_url + "/api/v1/catalog/products/" + self.product_id
        self.wait_for(lambda: self.fetch(catalog_url)["data"]["id"] == self.product_id, 30, "route to actual Catalog")
        assert self.docker("exec", self.gateway_id, "sha256sum", "/app.jar").split()[0] == digest
        self.save("gateway-artifact.json", {"jar_sha256": digest, "image": self.java, "url": self.gateway_url,
                                          "resolution": "Spring Cloud simple discovery on the private Docker network"})
        self.host_check("host-routed-gateway.json")

    @staticmethod
    def fetch(url):
        with urlopen(url, timeout=4) as reply:
            return json.load(reply)

    def preview(self, name):
        frontend = PRODUCT / "frontend"
        vite = list((frontend / "node_modules/.pnpm").glob("vite@*/node_modules/vite/bin/vite.js"))
        assert len(vite) == 1 and (frontend / name / "dist/index.html").is_file()
        args = [self.args.node, str(vite[0]), "preview", "--host", "127.0.0.1",
                "--port", str(PORTS[name]), "--strictPort"]
        with (self.directory / (name + ".log")).open("wb") as log:
            process = subprocess.Popen(args, cwd=frontend / name, stdout=log, stderr=subprocess.STDOUT,
                                       env={**os.environ, "VITE_API_PROXY_TARGET": self.gateway_url},
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            self.previews[name] = process
        url = f"http://127.0.0.1:{PORTS[name]}"
        catalog_url = url + "/api/v1/catalog/products/" + self.product_id
        self.wait_for(lambda: process.poll() is None and self.fetch(catalog_url)["data"]["id"] == self.product_id,
                      50, f"{name} compiled preview and backend route")
        return url

    def execute(self):
        self.stage = "owned-topology"
        self.open_lab()
        self.stage = "checkout-data"
        self.setup_customer()
        self.setup_sku()
        pending = self.checkout("browser-pending-checkout", price.OLD_PRICE)
        expired = self.checkout("browser-expired-clock-checkout", price.OLD_PRICE)
        self.sql(f"""UPDATE b1_trade.trade_order SET payment_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE
WHERE order_no='{expired}';""")
        self.host_check("host-after-stock-and-checkout.json")
        self.stage = "payment-and-frontend"
        self.service("payment", price.PAYMENT_SETTINGS, "payment-refund")
        self.host_check("host-payment-ready.json")
        self.gateway()
        urls = {name: self.preview(name) for name in PORTS}
        self.host_check("host-previews-ready.json")
        self.save("workspace.json", {"urls": urls, "productId": self.product_id, "skuId": self.sku_id,
                                    "pendingOrder": pending, "expiredOrder": expired,
                                    "source": self.result["source"], "gateway": self.gateway_url})
        print(json.dumps({"state": "READY", "urls": urls, "evidence": str(self.directory)}), flush=True)
        self.stage = "owned-browser-evidence-window"
        self.wait_for(lambda: (self.directory / "browser-complete.signal").is_file(),
                      self.args.hold_seconds, "browser session completion")
        facts = {order: self.facts(order) for order in (pending, expired)}
        self.save("browser-clock-payment-ledgers.json", facts)
        assert facts[pending]["payments"] == 1 and facts[expired]["payments"] == 0
        assert facts[pending]["amount"] == 39.8 and facts[pending]["paymentState"] == "PROCESSING"
        for ledger in facts.values():
            assert ledger["transactions"] == ledger["refunds"] == ledger["callbacks"] == ledger["successEvents"] == 0
        self.host_check("host-before-cleanup.json")
        assert (self.directory / "browser-complete.signal").read_text().strip() == "PASS_BROWSER_SCOPED"
        self.result.update(status="FIXTURE_COMPLETE", owned_environment=True)

    def cleanup(self):
        for process in reversed(list(self.previews.values())):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=8)
        super().cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    parser.add_argument("--node", required=True)
    parser.add_argument("--hold-seconds", type=int, default=600)
    runner = BrowserLab(parser.parse_args())
    try:
        runner.execute()
    except Exception as exc:
        runner.result.update(stage=runner.stage, status="INCONCLUSIVE", error=repr(exc))
    finally:
        runner.cleanup()
        runner.save("result.json", runner.result)
    print(json.dumps(runner.result))
    return 0 if runner.result["status"] == "FIXTURE_COMPLETE" and runner.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
