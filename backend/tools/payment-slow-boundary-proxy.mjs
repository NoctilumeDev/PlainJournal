// B1 fixture: an isolated Payment -> real Trade boundary, never a business stub.
import http from 'node:http';
import { setTimeout as sleep } from 'node:timers/promises';

let mode = 'baseline';
let active = 0;
let peak = 0;
const records = [];
const modes = new Set(['baseline', 'headers', 'body']);
function json(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(value));
}
http.createServer(async (req, res) => {
  if (req.url === '/__b1/state' && req.method === 'GET') {
    return json(res, 200, { mode, active, peak, records });
  }
  if (req.url?.startsWith('/__b1/mode/') && req.method === 'POST') {
    const next = req.url.slice('/__b1/mode/'.length);
    if (!modes.has(next) || active !== 0) return json(res, 409, { error: 'invalid mode or requests still active' });
    mode = next;
    return json(res, 200, { mode });
  }
  if (req.method !== 'GET' || !/^\/api\/v1\/trade\/internal\/orders\/B1-[A-Za-z0-9-]+\/payment-context$/.test(req.url ?? '')) {
    return json(res, 400, { error: 'outside fixture boundary' });
  }
  const record = { path: req.url, mode, started: Date.now(), upstreamStatus: null };
  records.push(record);
  active++;
  peak = Math.max(peak, active);
  try {
    const result = await new Promise((resolve, reject) => {
      const upstream = http.get({ hostname: 'trade-service', port: 8080, path: req.url,
        headers: { ...req.headers, host: 'trade-service:8080' } }, response => {
        const chunks = [];
        response.on('data', chunk => chunks.push(chunk));
        response.on('error', reject);
        response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks) }));
      });
      upstream.setTimeout(3000, () => upstream.destroy(new Error('real Trade did not respond in 3s')));
      upstream.on('error', reject);
    });
    record.upstreamStatus = result.status;
    record.upstreamFinished = Date.now();
    if (record.mode === 'headers') await sleep(2500);
    res.writeHead(result.status, { 'content-type': 'application/json', 'content-length': result.body.length });
    if (record.mode === 'body') {
      res.flushHeaders();
      // At least one real response byte reaches the client before the body stalls.
      res.write(result.body.subarray(0, 1));
      await sleep(2500);
      res.end(result.body.subarray(1));
    } else res.end(result.body);
  } catch (error) {
    record.error = String(error);
    if (!res.headersSent) json(res, 502, { error: 'fixture upstream failed' });
    else res.destroy();
  } finally {
    record.finished = Date.now();
    record.clientGone = res.destroyed;
    active--;
  }
}).listen(8080, '0.0.0.0');
