"""Bounded B1 witness on built Payment/Trade JARs; Python stdlib + Docker only.

Run after Maven package. Every run requires a NEW evidence directory and owns its
containers/network/tmpfs database. No business fixture replaces either service.
No host network fault injection, shared databases, dependency or runtime edits.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
from pathlib import Path
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
TOKEN_SECRET = 'b1-isolated-synthetic-jwt-secret-at-least-32-characters'
INTERNAL_SECRET = 'b1-isolated-synthetic-internal-token-at-least-32-characters'
DB_SECRET = 'b1-isolated-mysql-only'
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def token(role):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    payload = enc({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + enc({
        'sub': '91001', 'iss': 'ecommerce-identity', 'roles': [role],
        'iat': int(time.time()), 'exp': int(time.time()) + 1800})
    signature = base64.urlsafe_b64encode(hmac.new(TOKEN_SECRET.encode(), payload, hashlib.sha256).digest()).rstrip(b'=')
    return (payload + b'.' + signature).decode()


def request(url, body=None, role=None, key=None, timeout=8):
    headers = {'Content-Type': 'application/json'}
    if role:
        headers['Authorization'] = 'Bearer ' + token(role)
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    started = time.monotonic()
    try:
        with HTTP.open(req, timeout=timeout) as response:
            status, data = response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read().decode()
    try:
        data = json.loads(data)
    except ValueError:
        pass
    return {'status': status, 'ms': round((time.monotonic() - started) * 1000, 2), 'body': data}


class Run:
    def __init__(self, args):
        self.args = args
        self.directory = Path(args.evidence).resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        self.ident = 'pj-b1-' + uuid.uuid4().hex[:10]
        self.containers = []
        self.network_created = False
        self.stage = 'setup'
        self.result = {'status': 'INCONCLUSIVE', 'id': self.ident, 'phases': []}

    def save(self, name, value):
        (self.directory / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

    def command(self, *args, stdin=None, timeout=60, check=True):
        result = subprocess.run(list(args), input=stdin, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        with (self.directory / 'commands.jsonl').open('a', encoding='utf-8') as log:
            log.write(json.dumps({'command': args, 'returncode': result.returncode,
                                  'stdout': result.stdout, 'stderr': result.stderr}) + '\n')
        if check and result.returncode:
            raise RuntimeError(f'command failed ({result.returncode}): {args[:4]}: {result.stderr[-1200:]}')
        return result.stdout.strip()

    def docker(self, *args, **kwargs):
        return self.command(self.args.docker, *args, **kwargs)

    def host_check(self, name):
        raw = self.command(self.args.pwsh, '-NoProfile', '-File',
                           str(ROOT / 'backend/tools/check-verification-host.ps1'), '-AsJson', timeout=45, check=False)
        value = json.loads(raw)
        self.save(name, value)
        if not value['passed']:
            raise RuntimeError('host preflight rejected this topology')

    def container(self, suffix, image, extra, command):
        name = self.ident + '-' + suffix
        cid = self.docker('run', '-d', '--pull=never', '--name', name, '--label', 'codex.task=' + self.ident,
                          '--network', self.ident, *extra, image, *command)
        self.containers.append(cid)
        return cid

    def port(self, cid):
        return int(self.docker('port', cid, '8080/tcp').rsplit(':', 1)[1])

    def sql(self, query):
        return self.docker('exec', '-i', '-e', 'MYSQL_PWD=' + DB_SECRET, self.mysql,
                           'mysql', '-uroot', '-N', '-B', stdin=query)

    def wait_for(self, predicate, seconds, label):
        end = time.monotonic() + seconds
        last_error = None
        while time.monotonic() < end:
            try:
                if predicate():
                    return
            except Exception as error:
                last_error = str(error)
            time.sleep(1)
        raise RuntimeError(f'{label} not ready: {last_error}')

    def state(self):
        return request(self.proxy_url + '/__b1/state')['body']

    def mode(self, mode):
        self.wait_for(lambda: self.state()['active'] == 0, 12, 'proxy drain')
        response = request(self.proxy_url + '/__b1/mode/' + mode, {})
        if response['status'] != 200:
            raise RuntimeError('proxy mode change rejected')

    def seed(self, orders):
        # Synthetic order facts are setup only; both read and payment creation use real services.
        values = []
        for index, order in enumerate(orders, 1):
            values.append(f"({index},'{order}',91001,'{order}','{'0' * 64}','RSV-{order}',"
                          "'PRIMARY','PENDING_PAYMENT',39.80,39.80,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),"
                          "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
        self.sql('INSERT INTO b1_trade.trade_order (id,order_no,user_id,idempotency_key,request_hash,'
                 'reservation_no,warehouse_code,status,total_amount,original_amount,payment_deadline,created_at,updated_at) VALUES '
                 + ','.join(values) + ';')

    def create(self, order):
        response = request(self.payment_url + '/api/v1/payment/payments',
                           {'orderNo': order, 'channel': 'MOCK'}, 'CUSTOMER', 'idem-' + order)
        response['order'] = order
        return response

    def metrics(self):
        names = ['jvm.threads.live', 'tomcat.threads.busy', 'tomcat.threads.current',
                 'hikaricp.connections.active', 'hikaricp.connections.pending',
                 'resilience4j.bulkhead.available.concurrent.calls', 'resilience4j.circuitbreaker.state',
                 'ecommerce.http.client.resilience.rejections']
        values = {name: request(self.payment_url + '/actuator/metrics/' + name, role='ADMIN', timeout=3)
                  for name in names}
        # The unfiltered state metric is always the sum of all state gauges (1).
        # It cannot establish whether the circuit is OPEN or CLOSED.
        for state in ['open', 'closed', 'half_open']:
            values['circuit.' + state] = request(
                self.payment_url + '/actuator/metrics/resilience4j.circuitbreaker.state'
                '?tag=name:paymentTradePaymentContext&tag=state:' + state, role='ADMIN', timeout=3)
        return values

    def metric_value(self, values, name):
        response = values[name]
        if response['status'] != 200 or len(response['body'].get('measurements', [])) != 1:
            raise RuntimeError('required measurement unavailable: ' + name)
        return response['body']['measurements'][0]['value']

    def snapshot(self, name):
        self.save(name + '-metrics.json', self.metrics())
        raw = self.docker('stats', '--no-stream', '--format', '{{json .}}', *self.containers, timeout=20)
        (self.directory / (name + '-docker-stats.jsonl')).write_text(raw + '\n', encoding='utf-8')
        (self.directory / (name + '-payment-tcp.txt')).write_text(
            self.docker('exec', self.payment_cid, 'cat', '/proc/1/net/tcp', '/proc/1/net/tcp6'), encoding='utf-8')

    def phase(self, mode, orders, stable_payment):
        self.host_check(mode + '-host-preflight.json')
        self.stage = mode
        self.mode(mode)
        before = len(self.state()['records'])
        stop = threading.Event()
        reads, metrics, sampler_errors = [], [], []

        def sample():
            while not stop.is_set():
                try:
                    reads.append(request(self.payment_url + '/api/v1/payment/payments/' + stable_payment, role='CUSTOMER', timeout=3))
                    metrics.append({'at': time.time(), 'values': self.metrics()})
                except Exception as error:
                    sampler_errors.append(str(error))
                stop.wait(.15)

        sampler = threading.Thread(target=sample)
        sampler.start()
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(orders)) as executor:
                outcomes = list(executor.map(self.create, orders))
            # Keep observation running until the actual delayed responses have drained.
            self.wait_for(lambda: self.state()['active'] == 0, 12, 'delayed response drain')
        finally:
            stop.set()
            sampler.join(5)
        observed = self.state()['records'][before:]
        result = {'mode': mode, 'outcomes': outcomes, 'independentReads': reads, 'metrics': metrics,
                  'samplerErrors': sampler_errors, 'attempts': observed}
        self.save(mode + '-phase.json', result)  # Save first observations before assertions.
        self.result['phases'].append({'mode': mode, 'evidence': mode + '-phase.json'})
        assert not sampler.is_alive() and not sampler_errors, 'observer did not complete cleanly'
        for sample in metrics:
            required = ['jvm.threads.live', 'tomcat.threads.busy', 'tomcat.threads.current',
                        'hikaricp.connections.active', 'hikaricp.connections.pending']
            if any(sample['values'][name]['status'] != 200 for name in required):
                raise RuntimeError('required resource measurements unavailable')
        assert all(item['status'] == 503 and item['body']['code'] == 'REMOTE_DEPENDENCY_UNAVAILABLE'
                   for item in outcomes), 'slow boundary produced unexpected business outcome'
        assert max(item['ms'] for item in outcomes) <= 6000, '5s configured budget + 1s scheduling tolerance exceeded'
        assert reads and all(item['status'] == 200 and item['body']['data']['paymentNo'] == stable_payment for item in reads), 'independent reads failed'
        assert max(item['ms'] for item in reads) < 1000, 'independent business reads starved (>1s)'
        assert observed and all(item['upstreamStatus'] == 200 and 'error' not in item for item in observed), 'fault was not delay of a real successful response'
        attempts = {order: sum('/' + order + '/' in item['path'] for item in observed) for order in orders}
        assert max(attempts.values()) <= 2, 'per-request downstream amplification exceeded two attempts'
        assert sum(attempts.values()) == len(observed), 'unexpected boundary traffic'
        counts = self.sql("SELECT COUNT(*) FROM b1_payment.payment_order WHERE order_no IN (" + ','.join("'" + order + "'" for order in orders) + ');')
        assert counts == '0', 'a failed slow request persisted a payment'
        self.snapshot(mode)
        fault_metrics = self.metrics()
        self.save(mode + '-circuit-open.json', fault_metrics)
        assert self.metric_value(fault_metrics, 'circuit.open') == 1, 'circuit did not open after real failures'
        probe_before = len(self.state()['records'])
        probe = self.create(orders[0])
        self.save(mode + '-open-rejection.json', probe)
        assert probe['status'] == 503 and probe['body']['code'] == 'REMOTE_DEPENDENCY_UNAVAILABLE', 'open circuit did not reject'
        assert len(self.state()['records']) == probe_before, 'open circuit started another downstream request'
        self.host_check(mode + '-host-after.json')
        self.mode('baseline')
        # Let the real circuit time out; never force a transition through test APIs.
        time.sleep(11)
        recovered = [self.create(order) for order in orders]
        self.save(mode + '-recovery.json', recovered)
        assert all(item['status'] == 200 for item in recovered), 'business did not recover naturally'
        stable = [self.create(order) for order in orders]
        self.save(mode + '-idempotency.json', stable)
        assert [item['body']['data']['paymentNo'] for item in stable] == [item['body']['data']['paymentNo'] for item in recovered], 'recovery replay changed payment identity'
        current = self.state()['records'][before:]
        assert len(current) == len(observed) + len(orders), 'recovery/replay caused hidden downstream retries'
        recovered_metrics = self.metrics()
        self.save(mode + '-circuit-recovered.json', recovered_metrics)
        assert self.metric_value(recovered_metrics, 'circuit.closed') == 1, 'circuit did not close after successful recovery'

    def execute(self):
        self.host_check('preflight.json')
        sha = self.command('git', '-C', str(ROOT), 'rev-parse', 'HEAD')
        jars = {service: ROOT / f'backend/services/{service}-service/target/{service}-service.jar' for service in ['trade', 'payment']}
        images = {name: self.docker('image', 'inspect', '--format', '{{.Id}}', name)
                  for name in ['mysql:8.4.10', 'eclipse-temurin:17-jre', 'node:22-alpine']}
        self.save('contract.json', {'source': sha, 'workingTree': self.command('git', '-C', str(ROOT), 'status', '--short'),
            'jars': {key: {'path': str(value), 'sha256': hashlib.sha256(value.read_bytes()).hexdigest()} for key, value in jars.items()},
            'images': images, 'boundary': 'real Payment -> delay proxy -> real Trade -> MySQL',
            'latencyMs': 2500, 'maxInputConcurrency': 18, 'timeoutConnectMs': 500, 'timeoutReadMs': 1500,
            'maxAttempts': 2, 'bulkhead': 8, 'bulkheadWaitMs': 250, 'circuitOpenSeconds': 10,
            'requestBudgetMs': 5000, 'schedulingToleranceMs': 1000, 'independentReadLimitMs': 1000,
            'circuitEvidence': 'tagged OPEN/CLOSED gauges and open-state request with zero additional downstream attempts',
            'excluded': ['gateway', 'Nacos server/failover', 'MQ', 'whole site', 'order creation qualification', 'capacity ceiling'],
            'fixtureHashes': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                              for path in [Path(__file__), ROOT / 'backend/tools/payment-slow-boundary-proxy.mjs']}})
        # Dedicated bridge; only loopback ports are published for the observer.
        # Docker Desktop internal networks do not expose those published ports.
        self.docker('network', 'create', '--label', 'codex.task=' + self.ident, self.ident)
        self.network_created = True
        self.mysql = self.container('mysql', images['mysql:8.4.10'],
            ['--network-alias', 'b1-mysql', '--memory', '768m', '--cpus', '1', '--tmpfs', '/var/lib/mysql',
             '-e', 'MYSQL_ROOT_PASSWORD=' + DB_SECRET], ['--innodb-buffer-pool-size=134217728', '--max-connections=80'])
        self.wait_for(lambda: self.sql('SELECT 1;') == '1', 90, 'MySQL')
        self.sql('CREATE DATABASE b1_trade; CREATE DATABASE b1_payment;')
        common = ['--server.port=8080', '--server.tomcat.mbeanregistry.enabled=true',
                  '--spring.config.import=', '--spring.cloud.nacos.config.enabled=false',
                  '--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.nacos.config.import-check.enabled=false',
                  '--ecommerce.messaging.consumer-failure-retry.enabled=false']
        synthetic = {'IDENTITY_JWT_SECRET': TOKEN_SECRET, 'PAYMENT_INTERNAL_SERVICE_TOKEN': INTERNAL_SECRET,
                     'TRADE_INTERNAL_SERVICE_TOKEN': INTERNAL_SECRET,
                     'METRICS_SCRAPE_TOKEN': INTERNAL_SECRET, 'MOCK_PAYMENT_CALLBACK_SECRET': INTERNAL_SECRET,
                     'MYSQL_HOST': 'b1-mysql', 'MYSQL_PORT': '3306'}
        for service in ['trade', 'payment']:
            env = {**synthetic, service.upper() + '_DB_NAME': 'b1_' + service,
                   service.upper() + '_DB_USER': 'root', service.upper() + '_DB_PASSWORD': DB_SECRET}
            extra = ['--network-alias', service + '-service', '--memory', '768m', '--cpus', '1.5',
                     '-p', '127.0.0.1::8080', '--mount', f'type=bind,source={jars[service]},target=/app.jar,readonly']
            for key, value in env.items():
                extra += ['-e', key + '=' + value]
            if service == 'trade':
                disabled = ['outbox', 'flash-sale-consumer', 'payment-consumer', 'fulfillment-consumer',
                            'after-sale-fulfillment-consumer', 'after-sale-inventory-consumer', 'refund-result-consumer', 'reconciliation']
                options = ['--ecommerce.trade.' + name + '.enabled=false' for name in disabled]
                options += ['--ecommerce.trade.order.recovery-enabled=false']
            else:
                disabled = ['outbox', 'refund-consumer', 'refund-dispatch', 'reconciliation']
                options = ['--ecommerce.payment.' + name + '.enabled=false' for name in disabled]
                options += ['--spring.cloud.discovery.client.simple.instances.trade-service[0].uri=http://trade-delay:8080']
            cid = self.container(service, images['eclipse-temurin:17-jre'], extra,
                ['java', '-Xms128m', '-Xmx384m', '-XX:MaxMetaspaceSize=256m', '-jar', '/app.jar', *common, *options])
            setattr(self, service + '_url', 'http://127.0.0.1:' + str(self.port(cid)))
            setattr(self, service + '_cid', cid)
            self.wait_for(lambda: request(getattr(self, service + '_url') + '/actuator/health', timeout=2)['status'] == 200,
                          120, service)
        proxy = self.container('delay', images['node:22-alpine'],
            ['--network-alias', 'trade-delay', '--memory', '128m', '--cpus', '.5', '-p', '127.0.0.1::8080',
             '--mount', f'type=bind,source={ROOT / "backend/tools/payment-slow-boundary-proxy.mjs"},target=/proxy.mjs,readonly'],
            ['node', '/proxy.mjs'])
        self.proxy_url = 'http://127.0.0.1:' + str(self.port(proxy))
        self.wait_for(lambda: self.state()['mode'] == 'baseline', 15, 'proxy')
        self.host_check('runtime-preflight.json')
        for cid in self.containers:
            self.save(cid[:12] + '-inspect.json', json.loads(self.docker('inspect', cid)))
        baseline = [f'B1-base-{i}' for i in range(4)]
        headers = [f'B1-headers-{i}' for i in range(18)]
        body = [f'B1-body-{i}' for i in range(18)]
        self.seed(baseline + headers + body)
        self.stage = 'baseline'
        initial = [self.create(order) for order in baseline]
        self.save('baseline.json', initial)
        assert all(item['status'] == 200 for item in initial), 'baseline failed'
        assert len(self.state()['records']) == len(baseline), 'baseline has unexpected retries'
        self.snapshot('baseline')
        payment = initial[0]['body']['data']['paymentNo']
        self.phase('headers', headers, payment)
        self.phase('body', body, payment)
        self.snapshot('recovered')
        facts = self.sql('SELECT COUNT(*),COUNT(DISTINCT order_no),SUM(amount) FROM b1_payment.payment_order;'
                         'SELECT COUNT(*) FROM b1_payment.payment_transaction;'
                         'SELECT COUNT(*) FROM b1_payment.outbox_event;')
        self.save('business-readback.json', {'raw': facts})
        assert facts.splitlines() == ['40\t40\t1592.00', '0', '0'], 'unexpected business effects'
        self.result['status'] = 'PASS_SCOPED'

    def cleanup(self):
        errors = []
        for cid in reversed(self.containers):
            try:
                label = self.docker('inspect', '--format', '{{index .Config.Labels "codex.task"}}', cid)
                if label != self.ident:
                    raise RuntimeError('container ownership mismatch: ' + cid)
                (self.directory / (cid[:12] + '.log')).write_text(self.docker('logs', cid, check=False), encoding='utf-8')
                self.docker('stop', '-t', '15', cid, timeout=35)
                self.save(cid[:12] + '-stopped.json', json.loads(self.docker('inspect', cid)))
                self.docker('rm', cid)
            except Exception as error:
                errors.append(str(error))
        if self.network_created:
            try:
                label = self.docker('network', 'inspect', '--format', '{{index .Labels "codex.task"}}', self.ident)
                if label != self.ident:
                    raise RuntimeError('network ownership mismatch')
                self.docker('network', 'rm', self.ident)
            except Exception as error:
                errors.append(str(error))
        remaining = self.docker('ps', '-a', '-q', '--filter', 'label=codex.task=' + self.ident)
        self.save('cleanup.json', {'errors': errors, 'remainingContainers': remaining})
        if errors or remaining:
            self.result['cleanup'] = 'INCOMPLETE'
        else:
            self.result['cleanup'] = 'PASS'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--docker', default=shutil.which('docker'))
    parser.add_argument('--pwsh', default=shutil.which('pwsh'))
    args = parser.parse_args()
    if not args.docker or not args.pwsh:
        parser.error('Docker and PowerShell 7 are required')
    run = Run(args)
    try:
        run.execute()
    except AssertionError as error:
        run.result.update(status='FAIL' if run.stage in ['headers', 'body'] else 'INCONCLUSIVE',
                          stage=run.stage, error=str(error))
    except Exception as error:
        run.result.update(status='INCONCLUSIVE', stage=run.stage, error=repr(error))
    finally:
        run.cleanup()
        run.save('result.json', run.result)
        run.save('sha256.json', {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                                for file in run.directory.iterdir() if file.is_file() and file.name != 'sha256.json'})
    print(json.dumps(run.result, ensure_ascii=False, indent=2), flush=True)
    return 0 if run.result['status'] == 'PASS_SCOPED' and run.result['cleanup'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
