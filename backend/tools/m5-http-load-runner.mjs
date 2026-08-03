import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

const MAX_ERROR_SAMPLES = 20;
const ENV_PATTERN = /\$\{ENV:([A-Z_][A-Z0-9_]*)\}/g;

function requireInteger(value, name, minimum = 0) {
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer >= ${minimum}.`);
  }
  return value;
}

function requireNumber(value, name, minimum, maximum) {
  if (typeof value !== 'number' || Number.isNaN(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be a number between ${minimum} and ${maximum}.`);
  }
  return value;
}

function resolveEnvironmentValue(value) {
  if (typeof value === 'string') {
    return value.replace(ENV_PATTERN, (_, name) => {
      const resolved = process.env[name];
      if (resolved === undefined) {
        throw new Error(`Missing environment variable required by load configuration: ${name}`);
      }
      return resolved;
    });
  }
  if (Array.isArray(value)) {
    return value.map(resolveEnvironmentValue);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, nested]) => [key, resolveEnvironmentValue(nested)]),
    );
  }
  return value;
}

function normalizeExpectedStatuses(value, scenarioName) {
  const statuses = value ?? [200];
  if (!Array.isArray(statuses) || statuses.length === 0) {
    throw new Error(`Scenario ${scenarioName} must define at least one expected HTTP status.`);
  }
  for (const status of statuses) {
    requireInteger(status, `Scenario ${scenarioName} expected status`, 100);
    if (status > 599) {
      throw new Error(`Scenario ${scenarioName} has an invalid HTTP status: ${status}`);
    }
  }
  return [...new Set(statuses)];
}

function normalizeHeaders(value) {
  return Object.fromEntries(
    Object.entries(value ?? {}).map(([key, headerValue]) => [key, String(headerValue)]),
  );
}

function normalizeBody(value, headers) {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value === 'string') {
    return value;
  }
  if (!Object.keys(headers).some((key) => key.toLowerCase() === 'content-type')) {
    headers['Content-Type'] = 'application/json';
  }
  return JSON.stringify(value);
}

function normalizeRequestVariant(rawVariant, scenarioName, index, defaults) {
  if (!rawVariant || typeof rawVariant !== 'object') {
    throw new Error(`Variant ${index} in scenario ${scenarioName} must be an object.`);
  }
  const url = String(rawVariant.url ?? '').trim();
  if (!url) {
    throw new Error(`Variant ${index} in scenario ${scenarioName} requires a URL.`);
  }
  const parsedUrl = new URL(url);
  if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
    throw new Error(`Variant ${index} in scenario ${scenarioName} must use HTTP or HTTPS.`);
  }
  const headers = {
    ...defaults.headers,
    ...normalizeHeaders(rawVariant.headers),
  };
  return {
    url,
    method: String(rawVariant.method ?? defaults.method).toUpperCase(),
    headers,
    body: normalizeBody(
      rawVariant.body === undefined ? defaults.rawBody : rawVariant.body,
      headers,
    ),
  };
}

function normalizeScenario(rawScenario, index) {
  if (!rawScenario || typeof rawScenario !== 'object') {
    throw new Error(`Scenario at index ${index} must be an object.`);
  }
  const name = String(rawScenario.name ?? '').trim();
  if (!name) {
    throw new Error(`Scenario at index ${index} requires a name.`);
  }
  if (rawScenario.variants !== undefined &&
      (rawScenario.url !== undefined || rawScenario.urls !== undefined)) {
    throw new Error(`Scenario ${name} cannot define variants together with url or urls.`);
  }
  if (rawScenario.url !== undefined && rawScenario.urls !== undefined) {
    throw new Error(`Scenario ${name} cannot define both url and urls.`);
  }

  const method = String(rawScenario.method ?? 'GET').toUpperCase();
  const headers = normalizeHeaders(rawScenario.headers);
  let requests;
  if (rawScenario.variants !== undefined) {
    if (!Array.isArray(rawScenario.variants) || rawScenario.variants.length === 0) {
      throw new Error(`Scenario ${name} requires at least one variant.`);
    }
    requests = rawScenario.variants.map((variant, variantIndex) =>
      normalizeRequestVariant(variant, name, variantIndex, {
        method,
        headers,
        rawBody: rawScenario.body,
      }));
  } else {
    const urls = rawScenario.urls === undefined
      ? [String(rawScenario.url ?? '').trim()]
      : rawScenario.urls.map((value) => String(value).trim());
    if (urls.length === 0 || urls.some((url) => !url)) {
      throw new Error(`Scenario ${name} requires at least one URL.`);
    }
    requests = urls.map((url, urlIndex) =>
      normalizeRequestVariant({ url }, name, urlIndex, {
        method,
        headers,
        rawBody: rawScenario.body,
      }));
  }

  return {
    name,
    requests,
    weight: requireInteger(rawScenario.weight ?? 1, `Scenario ${name} weight`, 1),
    expectedStatuses: normalizeExpectedStatuses(rawScenario.expectedStatuses, name),
    expectedJsonCode:
      rawScenario.expectedJsonCode === undefined
        ? undefined
        : String(rawScenario.expectedJsonCode),
    expectedBodyIncludes:
      rawScenario.expectedBodyIncludes === undefined
        ? undefined
        : String(rawScenario.expectedBodyIncludes),
  };
}

export function normalizeConfiguration(rawConfiguration) {
  const raw = resolveEnvironmentValue(rawConfiguration);
  if (!raw || typeof raw !== 'object') {
    throw new Error('Load configuration must be an object.');
  }
  if (raw.schemaVersion !== 1) {
    throw new Error('Load configuration schemaVersion must be 1.');
  }
  if (!Array.isArray(raw.scenarios) || raw.scenarios.length === 0) {
    throw new Error('Load configuration requires at least one scenario.');
  }

  const requests = requireInteger(raw.requests, 'requests', 1);
  const concurrency = requireInteger(raw.concurrency, 'concurrency', 1);
  if (concurrency > requests) {
    throw new Error('concurrency cannot be greater than requests.');
  }

  return {
    schemaVersion: 1,
    name: String(raw.name ?? 'unnamed-load').trim() || 'unnamed-load',
    requests,
    concurrency,
    warmupRequests: requireInteger(raw.warmupRequests ?? 0, 'warmupRequests', 0),
    timeoutMs: requireInteger(raw.timeoutMs ?? 10_000, 'timeoutMs', 1),
    maxErrorRate: requireNumber(raw.maxErrorRate ?? 0, 'maxErrorRate', 0, 1),
    scenarios: raw.scenarios.map(normalizeScenario),
  };
}

function percentile(sortedValues, ratio) {
  if (sortedValues.length === 0) {
    return 0;
  }
  const index = Math.max(0, Math.ceil(sortedValues.length * ratio) - 1);
  return Number(sortedValues[index].toFixed(2));
}

function summarizeLatency(records) {
  if (records.length === 0) {
    return {
      minMs: 0,
      p50Ms: 0,
      p95Ms: 0,
      p99Ms: 0,
      maxMs: 0,
      averageMs: 0,
    };
  }
  const sorted = records.map((record) => record.latencyMs).sort((left, right) => left - right);
  const sum = sorted.reduce((total, value) => total + value, 0);
  return {
    minMs: Number(sorted[0].toFixed(2)),
    p50Ms: percentile(sorted, 0.5),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: Number(sorted.at(-1).toFixed(2)),
    averageMs: Number((sum / sorted.length).toFixed(2)),
  };
}

function summarizeRecords(records, elapsedMs) {
  const statusCodes = {};
  let transportErrors = 0;
  let validationErrors = 0;
  let responseBytes = 0;
  const errorSamples = [];

  for (const record of records) {
    const statusKey = record.status === null ? 'TRANSPORT_ERROR' : String(record.status);
    statusCodes[statusKey] = (statusCodes[statusKey] ?? 0) + 1;
    responseBytes += record.responseBytes;
    if (record.errorType === 'transport') {
      transportErrors += 1;
    } else if (record.errorType === 'validation') {
      validationErrors += 1;
    }
    if (record.errorMessage && errorSamples.length < MAX_ERROR_SAMPLES) {
      const sample = {
        scenario: record.scenario,
        url: record.url,
        type: record.errorType,
        message: record.errorMessage,
        status: record.status,
        latencyMs: Number(record.latencyMs.toFixed(2)),
      };
      if (record.responseCode !== undefined) {
        sample.responseCode = record.responseCode;
      }
      if (record.responseBodyExcerpt !== undefined) {
        sample.responseBodyExcerpt = record.responseBodyExcerpt;
      }
      errorSamples.push(sample);
    }
  }

  const totalErrors = transportErrors + validationErrors;
  return {
    requests: records.length,
    successes: records.length - totalErrors,
    transportErrors,
    validationErrors,
    errorRate: records.length === 0 ? 0 : Number((totalErrors / records.length).toFixed(6)),
    elapsedSeconds: Number((elapsedMs / 1000).toFixed(3)),
    requestsPerSecond:
      elapsedMs <= 0 ? 0 : Number((records.length / (elapsedMs / 1000)).toFixed(2)),
    responseBytes,
    statusCodes,
    latency: summarizeLatency(records),
    errorSamples,
  };
}

async function executeRequest(scenario, requestVariant, timeoutMs, fetchImplementation) {
  const startedAt = performance.now();
  let status = null;
  let responseBytes = 0;
  const { url, method, headers, body } = requestVariant;
  try {
    const response = await fetchImplementation(url, {
      method,
      headers,
      body,
      redirect: 'manual',
      signal: AbortSignal.timeout(timeoutMs),
    });
    status = response.status;
    const responseBody = await response.text();
    responseBytes = Buffer.byteLength(responseBody);
    let responseCode;
    try {
      responseCode = String(JSON.parse(responseBody)?.code);
    } catch {
      responseCode = undefined;
    }
    const responseBodyExcerpt = responseBody.slice(0, 500);

    if (!scenario.expectedStatuses.includes(response.status)) {
      return {
        scenario: scenario.name,
        url,
        status,
        responseBytes,
        latencyMs: performance.now() - startedAt,
        errorType: 'validation',
        errorMessage: `Expected HTTP ${scenario.expectedStatuses.join('/')} but received ${response.status}.`,
        responseCode,
        responseBodyExcerpt,
      };
    }
    if (scenario.expectedBodyIncludes !== undefined &&
        !responseBody.includes(scenario.expectedBodyIncludes)) {
      return {
        scenario: scenario.name,
        url,
        status,
        responseBytes,
        latencyMs: performance.now() - startedAt,
        errorType: 'validation',
        errorMessage: `Response body did not include ${scenario.expectedBodyIncludes}.`,
        responseCode,
        responseBodyExcerpt,
      };
    }
    if (scenario.expectedJsonCode !== undefined) {
      let payload;
      try {
        payload = JSON.parse(responseBody);
      } catch {
        return {
          scenario: scenario.name,
          url,
          status,
          responseBytes,
          latencyMs: performance.now() - startedAt,
          errorType: 'validation',
          errorMessage: 'Response was not valid JSON.',
          responseBodyExcerpt,
        };
      }
      if (String(payload?.code) !== scenario.expectedJsonCode) {
        return {
          scenario: scenario.name,
          url,
          status,
          responseBytes,
          latencyMs: performance.now() - startedAt,
          errorType: 'validation',
          errorMessage: `Expected JSON code ${scenario.expectedJsonCode} but received ${payload?.code}.`,
          responseCode,
          responseBodyExcerpt,
        };
      }
    }
    return {
      scenario: scenario.name,
      url,
      status,
      responseBytes,
      latencyMs: performance.now() - startedAt,
      errorType: null,
      errorMessage: null,
    };
  } catch (error) {
    return {
      scenario: scenario.name,
      url,
      status,
      responseBytes,
      latencyMs: performance.now() - startedAt,
      errorType: 'transport',
      errorMessage: error instanceof Error ? error.message : String(error),
    };
  }
}

async function executeBatch(configuration, requestCount, concurrency, fetchImplementation) {
  const schedule = configuration.scenarios.flatMap((scenario) =>
    Array.from({ length: scenario.weight }, () => scenario));
  const records = [];
  const scenarioCursors = new Map();
  let cursor = 0;

  async function worker() {
    while (true) {
      const requestIndex = cursor;
      cursor += 1;
      if (requestIndex >= requestCount) {
        return;
      }
      const scenario = schedule[requestIndex % schedule.length];
      const scenarioCursor = scenarioCursors.get(scenario.name) ?? 0;
      scenarioCursors.set(scenario.name, scenarioCursor + 1);
      const requestVariant = scenario.requests[scenarioCursor % scenario.requests.length];
      records.push(await executeRequest(
        scenario,
        requestVariant,
        configuration.timeoutMs,
        fetchImplementation,
      ));
    }
  }

  const startedAt = performance.now();
  await Promise.all(
    Array.from({ length: Math.min(concurrency, requestCount) }, () => worker()),
  );
  return {
    records,
    elapsedMs: performance.now() - startedAt,
  };
}

export async function runBenchmark(rawConfiguration, options = {}) {
  const configuration = normalizeConfiguration(rawConfiguration);
  const fetchImplementation = options.fetchImplementation ?? fetch;

  if (configuration.warmupRequests > 0) {
    await executeBatch(
      configuration,
      configuration.warmupRequests,
      Math.min(configuration.concurrency, configuration.warmupRequests),
      fetchImplementation,
    );
  }

  const { records, elapsedMs } = await executeBatch(
    configuration,
    configuration.requests,
    configuration.concurrency,
    fetchImplementation,
  );
  const aggregate = summarizeRecords(records, elapsedMs);
  const scenarios = Object.fromEntries(
    configuration.scenarios.map((scenario) => {
      const scenarioRecords = records.filter((record) => record.scenario === scenario.name);
      const scenarioElapsedMs = scenarioRecords.length === 0 ? 0 : elapsedMs;
      return [scenario.name, summarizeRecords(scenarioRecords, scenarioElapsedMs)];
    }),
  );
  const passed = aggregate.errorRate <= configuration.maxErrorRate;

  return {
    schemaVersion: 1,
    generatedAtUtc: new Date().toISOString(),
    nodeVersion: process.version,
    name: configuration.name,
    parameters: {
      requests: configuration.requests,
      concurrency: configuration.concurrency,
      warmupRequests: configuration.warmupRequests,
      timeoutMs: configuration.timeoutMs,
      maxErrorRate: configuration.maxErrorRate,
    },
    aggregate,
    scenarios,
    passed,
  };
}

async function main() {
  const [configurationPath, outputPath] = process.argv.slice(2);
  if (!configurationPath || !outputPath) {
    throw new Error(
      'Usage: node m5-http-load-runner.mjs <configuration.json> <output.json>',
    );
  }
  const rawConfiguration = JSON.parse(await readFile(resolve(configurationPath), 'utf8'));
  const result = await runBenchmark(rawConfiguration);
  const resolvedOutputPath = resolve(outputPath);
  await mkdir(dirname(resolvedOutputPath), { recursive: true });
  await writeFile(resolvedOutputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({
    name: result.name,
    passed: result.passed,
    requests: result.aggregate.requests,
    errors: result.aggregate.transportErrors + result.aggregate.validationErrors,
    errorRate: result.aggregate.errorRate,
    requestsPerSecond: result.aggregate.requestsPerSecond,
    p50Ms: result.aggregate.latency.p50Ms,
    p95Ms: result.aggregate.latency.p95Ms,
    p99Ms: result.aggregate.latency.p99Ms,
    output: resolvedOutputPath,
  }));
  if (!result.passed) {
    process.exitCode = 2;
  }
}

const isCommandLineEntry =
  process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isCommandLineEntry) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.stack : String(error));
    process.exitCode = 1;
  });
}
