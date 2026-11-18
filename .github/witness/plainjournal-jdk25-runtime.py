"""Read back the maintained Gateway/Trade images in a disposable topology."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil

HERE = Path(os.environ["PJ_WITNESS_INPUTS"])
REPO = Path(os.environ["PJ_SOURCE"])
tool = REPO / "backend/tools/run-payment-slow-boundary.py"
spec = importlib.util.spec_from_file_location("existing_runtime_runner", tool)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class ImageVerification(runtime.Run):
    def __init__(self, args):
        super().__init__(args)
        self.ident = self.ident.replace("pj-b1-", "pj-jdk25-")
        self.result["id"] = self.ident

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

    def read(self, cid, url, headers=()):
        options = [item for header in headers for item in ("--header", header)]
        body = self.docker("exec", cid, "wget", "-q", "-T", "8", "-O", "-", *options, url)
        return json.loads(body)

    def jre_and_jar(self, cid, image, expected):
        jre = self.docker("exec", cid, "sh", "-c", "java -version 2>&1")
        jar = self.docker("exec", cid, "sha256sum", "/opt/plainjournal/app.jar").split()[0]
        user = self.docker("exec", cid, "id", "-u")
        if 'version "25.0.5"' not in jre or jar != expected or user != "10001":
            raise AssertionError("packaged JRE, JAR or runtime user did not match")
        return {"image": image, "jre": jre, "jar_sha256": jar, "uid": user}

    def execute(self):
        self.stage = "source-and-host"
        sha = self.command("git", "-C", str(REPO), "rev-parse", "HEAD")
        if sha != "c8b66664fe0069753dc86b29bd1ce7438b71c921":
            raise RuntimeError(f"unexpected checkout {sha}")
        if self.command("git", "-C", str(REPO), "status", "--porcelain"):
            raise RuntimeError("runtime evidence needs a clean checkout")
        self.host_check("host-before.json")
        expected = json.loads((HERE / "candidate-jars.json").read_text(encoding="utf-8-sig"))
        gateway_image = (HERE / "jdk25-gateway-image-id.txt").read_text().strip()
        if self.docker("image", "inspect", "--format", '{{ index .Config.Labels "org.opencontainers.image.revision" }}', gateway_image) != sha:
            raise AssertionError("unexpected gateway image source")
        trade_image = (HERE / "jdk25-trade-image-id.txt").read_text().strip()
        if self.docker("image", "inspect", "--format", '{{ index .Config.Labels "org.opencontainers.image.revision" }}', trade_image) != sha:
            raise AssertionError("unexpected trade image source")
        mysql_image = self.image(os.environ["PJ_MYSQL_REF"])
        nacos_image = self.image(os.environ["PJ_NACOS_REF"])
        self.save("inputs.json", {"source": sha, "jar_source": "c8b66664fe0069753dc86b29bd1ce7438b71c921",
                                 "mysql_image": mysql_image, "nacos_image": nacos_image,
                                 "gateway_image": gateway_image, "trade_image": trade_image,
                                 "mysql_reference": os.environ["PJ_MYSQL_REF"], "nacos_reference": os.environ["PJ_NACOS_REF"],
                                 "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
        self.docker("network", "create", "--internal", "--label", "codex.task=" + self.ident, self.ident)
        self.network_created = True
        self.stage = "datastore"
        self.mysql = self.container("mysql", mysql_image, [
            "--network-alias", "mysql", "--memory", "620m", "--cpus", "1.5",
            "--tmpfs", "/var/lib/mysql:rw,size=600m",
            "-e", "MYSQL_ROOT_PASSWORD=" + runtime.DB_SECRET, "-e", "MYSQL_ROOT_HOST=%",
            "-e", "MYSQL_DATABASE=b1_trade", "-e", "TZ=UTC",
        ], ["--default-time-zone=+00:00", "--innodb-buffer-pool-size=64M"])
        self.wait_for(lambda: self.sql("SELECT 1") == "1", 75, "isolated MySQL")
        self.stage = "nacos"
        nacos = self.container("nacos", nacos_image, [
            "--network-alias", "nacos", "--memory", "560m", "--cpus", "1.5",
            "-e", "MODE=standalone", "-e", "PREFER_HOST_MODE=hostname", "-e", "NACOS_AUTH_ENABLE=false",
            "-e", "NACOS_AUTH_TOKEN=" + base64.b64encode(secrets.token_bytes(48)).decode(),
            "-e", "NACOS_AUTH_IDENTITY_KEY=isolated", "-e", "NACOS_AUTH_IDENTITY_VALUE=runtime-proof",
            "-e", "JVM_XMS=128m", "-e", "JVM_XMX=320m", "-e", "JVM_XMN=64m",
        ], [])
        def nacos_ready():
            self.docker("exec", nacos, "curl", "-fsS", "-m", "5",
                        "http://127.0.0.1:8080/v3/console/health/readiness")
            return True
        self.wait_for(nacos_ready, 95, "isolated Nacos")
        self.host_check("host-after-middleware.json")
        common = ["--memory", "540m", "--cpus", "1.5",
                  "-e", "IDENTITY_JWT_SECRET=" + runtime.TOKEN_SECRET,
                  "-e", "JAVA_TOOL_OPTIONS=-Xms64m -Xmx288m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=/tmp/plainjournal"]
        nacos_args = ["--spring.config.import=", "--spring.cloud.nacos.server-addr=nacos:8848",
                      "--spring.cloud.nacos.config.enabled=false", "--spring.cloud.nacos.config.import-check.enabled=false",
                      "--management.otlp.tracing.export.enabled=false"]
        self.stage = "trade"
        switches = [
            "--spring.datasource.url=jdbc:mysql://mysql:3306/b1_trade?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true",
            "--spring.datasource.username=root", "--spring.datasource.password=" + runtime.DB_SECRET,
            "--spring.cloud.nacos.discovery.enabled=true",
            "--ecommerce.foundation.configuration-source=jdk25-runtime",
            "--ecommerce.trade.order.recovery-enabled=false", "--ecommerce.messaging.consumer-failure-retry.enabled=false",
        ]
        for feature in ("outbox", "flash-sale-consumer", "payment-consumer", "fulfillment-consumer",
                        "after-sale-fulfillment-consumer", "after-sale-inventory-consumer",
                        "refund-result-consumer", "reconciliation"):
            switches.append(f"--ecommerce.trade.{feature}.enabled=false")
        trade = self.container("trade", trade_image, [
            *common,
            "-e", "PAYMENT_INTERNAL_SERVICE_TOKEN=" + runtime.INTERNAL_SECRET,
            "-e", "TRADE_INTERNAL_SERVICE_TOKEN=" + secrets.token_hex(32),
            "-e", "METRICS_SCRAPE_TOKEN=" + secrets.token_hex(32),
        ], [*nacos_args, *switches])
        trade_url = "http://127.0.0.1:18104"
        self.wait_for(lambda: self.read(trade, trade_url + "/actuator/health/readiness")["status"] == "UP",
                      100, "Trade boot and Flyway")
        self.host_check("host-after-trade.json")
        trade_info = self.jre_and_jar(trade, trade_image, expected["backend/services/trade-service/target/trade-service.jar"])
        migrations = self.sql("SELECT GROUP_CONCAT(CAST(version AS UNSIGNED) ORDER BY CAST(version AS UNSIGNED)) "
                              "FROM b1_trade.flyway_schema_history WHERE success=1")
        if migrations != ",".join(map(str, range(1, 21))):
            raise AssertionError(f"unexpected Trade migration set: {migrations}")
        self.seed(["JDK25-FIXTURE"])
        headers = ("X-Internal-Service: payment-service", "X-Internal-Token: " + runtime.INTERNAL_SECRET)
        payment_context = self.read(trade, trade_url + "/api/v1/trade/internal/orders/JDK25-FIXTURE/payment-context",
                                    headers)["data"]
        if payment_context["status"] != "PENDING_PAYMENT" or payment_context["totalAmount"] != 39.8:
            raise AssertionError("Trade payment-context changed")
        self.stage = "gateway"
        gateway = self.container("gateway", gateway_image, common, [
            *nacos_args, "--spring.cloud.nacos.discovery.register-enabled=false",
            "--ecommerce.gateway.rate-limit.redis-enabled=false", "--management.health.redis.enabled=false",
        ])
        gateway_url = "http://127.0.0.1:18000"
        self.wait_for(lambda: self.read(gateway, gateway_url + "/actuator/health/liveness")["status"] == "UP",
                      95, "Gateway container")
        self.host_check("host-after-gateway.json")
        gateway_info = self.jre_and_jar(gateway, gateway_image, expected["backend/ecommerce-gateway/target/ecommerce-gateway.jar"])
        route = gateway_url + "/api/v1/trade/status"
        def routed():
            return self.read(gateway, route)["data"]["configurationSource"] == "jdk25-runtime"
        self.wait_for(routed, 70, "Gateway/Nacos/Trade route")
        responses = [self.read(gateway, route) for _ in range(5)]
        if any(row["data"]["configurationSource"] != "jdk25-runtime" for row in responses):
            raise AssertionError("sampled Gateway route changed")
        self.host_check("host-after-requests.json")
        self.save("runtime.json", {"gateway": gateway_info, "trade": trade_info, "mysql": self.sql("SELECT VERSION()"),
                                   "migrations": migrations, "payment_context": payment_context, "gateway_responses": responses})
        self.result.update(status="PASS_SCOPED", stage=self.stage, routed=len(responses))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--docker", default=shutil.which("docker"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh"))
    run = ImageVerification(parser.parse_args())
    try:
        run.execute()
    except Exception as error:
        run.result.update(stage=run.stage, error=repr(error))
    finally:
        run.cleanup()
        run.save("result.json", run.result)
        run.save("sha256.json", {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                                for file in run.directory.iterdir() if file.is_file() and file.name != "sha256.json"})
    print(json.dumps(run.result))
    return 0 if run.result["status"] == "PASS_SCOPED" and run.result["cleanup"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
