import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';
import { runBenchmark } from './m5-http-load-runner.mjs';

let server;
let baseUrl;
const requestCounts = new Map();
const receivedRequests = [];

before(async () => {
  server = createServer((request, response) => {
    requestCounts.set(request.url, (requestCounts.get(request.url) ?? 0) + 1);
    const body = [];
    request.on('data', (chunk) => body.push(chunk));
    request.on('end', () => {
      receivedRequests.push({
        url: request.url,
        method: request.method,
        authorization: request.headers.authorization,
        body: Buffer.concat(body).toString('utf8'),
      });
      if (request.url === '/created') {
        response.writeHead(201, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ code: 'CREATED' }));
        return;
      }
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ code: 'OK' }));
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  baseUrl = `http://127.0.0.1:${address.port}`;
});

after(async () => {
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
});

test('records weighted scenarios, latency percentiles and zero-error throughput', async () => {
  const result = await runBenchmark({
    schemaVersion: 1,
    name: 'runner-success',
    requests: 60,
    concurrency: 6,
    warmupRequests: 6,
    timeoutMs: 2_000,
    maxErrorRate: 0,
    scenarios: [
      {
        name: 'ok',
        url: `${baseUrl}/ok`,
        expectedStatuses: [200],
        expectedJsonCode: 'OK',
        weight: 2,
      },
      {
        name: 'created',
        url: `${baseUrl}/created`,
        expectedStatuses: [201],
        expectedJsonCode: 'CREATED',
        weight: 1,
      },
    ],
  });

  assert.equal(result.passed, true);
  assert.equal(result.aggregate.requests, 60);
  assert.equal(result.aggregate.successes, 60);
  assert.equal(result.aggregate.errorRate, 0);
  assert.equal(result.scenarios.ok.requests, 40);
  assert.equal(result.scenarios.created.requests, 20);
  assert.ok(result.aggregate.requestsPerSecond > 0);
  assert.ok(result.aggregate.latency.p95Ms >= result.aggregate.latency.p50Ms);
  assert.ok(result.aggregate.latency.p99Ms >= result.aggregate.latency.p95Ms);
});

test('counts response-contract mismatches as validation errors', async () => {
  const result = await runBenchmark({
    schemaVersion: 1,
    name: 'runner-validation-failure',
    requests: 10,
    concurrency: 2,
    maxErrorRate: 0,
    scenarios: [
      {
        name: 'wrong-code',
        url: `${baseUrl}/ok`,
        expectedStatuses: [200],
        expectedJsonCode: 'NOT_OK',
      },
    ],
  });

  assert.equal(result.passed, false);
  assert.equal(result.aggregate.transportErrors, 0);
  assert.equal(result.aggregate.validationErrors, 10);
  assert.equal(result.aggregate.errorRate, 1);
  assert.equal(result.aggregate.errorSamples[0].type, 'validation');
  assert.equal(result.aggregate.errorSamples[0].status, 200);
  assert.equal(result.aggregate.errorSamples[0].responseCode, 'OK');
  assert.match(result.aggregate.errorSamples[0].responseBodyExcerpt, /"code":"OK"/);
  assert.ok(result.aggregate.errorSamples[0].latencyMs >= 0);
});

test('rotates through a scenario URL pool instead of pinning every request to one record', async () => {
  const firstPath = '/pooled-a';
  const secondPath = '/pooled-b';
  const firstBefore = requestCounts.get(firstPath) ?? 0;
  const secondBefore = requestCounts.get(secondPath) ?? 0;

  const result = await runBenchmark({
    schemaVersion: 1,
    name: 'runner-url-pool',
    requests: 12,
    concurrency: 3,
    maxErrorRate: 0,
    scenarios: [
      {
        name: 'pooled',
        urls: [
          `${baseUrl}${firstPath}`,
          `${baseUrl}${secondPath}`,
        ],
        expectedStatuses: [200],
        expectedJsonCode: 'OK',
      },
    ],
  });

  assert.equal(result.passed, true);
  assert.equal((requestCounts.get(firstPath) ?? 0) - firstBefore, 6);
  assert.equal((requestCounts.get(secondPath) ?? 0) - secondBefore, 6);
});

test('rotates complete request variants with independent headers and bodies', async () => {
  const startIndex = receivedRequests.length;
  const result = await runBenchmark({
    schemaVersion: 1,
    name: 'runner-request-variants',
    requests: 4,
    concurrency: 2,
    maxErrorRate: 0,
    scenarios: [
      {
        name: 'variant',
        method: 'PUT',
        headers: {
          'X-Suite': 'm5',
        },
        expectedStatuses: [200],
        expectedJsonCode: 'OK',
        variants: [
          {
            url: `${baseUrl}/variant-a`,
            headers: {
              Authorization: 'Bearer token-a',
            },
            body: {
              quantity: 1,
            },
          },
          {
            url: `${baseUrl}/variant-b`,
            headers: {
              Authorization: 'Bearer token-b',
            },
            body: {
              quantity: 2,
            },
          },
        ],
      },
    ],
  });

  const requests = receivedRequests.slice(startIndex);
  assert.equal(result.passed, true);
  assert.deepEqual(
    requests.map((request) => ({
      url: request.url,
      method: request.method,
      authorization: request.authorization,
      body: request.body,
    })),
    [
      {
        url: '/variant-a',
        method: 'PUT',
        authorization: 'Bearer token-a',
        body: '{"quantity":1}',
      },
      {
        url: '/variant-b',
        method: 'PUT',
        authorization: 'Bearer token-b',
        body: '{"quantity":2}',
      },
      {
        url: '/variant-a',
        method: 'PUT',
        authorization: 'Bearer token-a',
        body: '{"quantity":1}',
      },
      {
        url: '/variant-b',
        method: 'PUT',
        authorization: 'Bearer token-b',
        body: '{"quantity":2}',
      },
    ],
  );
});
