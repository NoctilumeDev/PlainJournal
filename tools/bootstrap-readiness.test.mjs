import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const bootstrapPath = path.join(
  repositoryRoot,
  "deploy",
  "docker",
  "bootstrap-resources.ps1",
);
const environmentExamplePath = path.join(
  repositoryRoot,
  "deploy",
  "docker",
  ".env.example",
);
const composePath = path.join(repositoryRoot, "deploy", "docker", "compose.yml");
const legacyNacosTokenPlaceholder =
  "replace-with-base64-token-longer-than-32-bytes";
const invalidNacosTokenDiagnostic =
  /canonical standard Base64.*least 32 bytes/u;
const concurrentBootstrapDiagnostic =
  /already preparing this.*environment/u;

async function createBootstrapFixture(transformEnvironment = (value) => value) {
  const fixture = await fs.mkdtemp(
    path.join(os.tmpdir(), "plainjournal-bootstrap-readiness-"),
  );
  await fs.copyFile(bootstrapPath, path.join(fixture, "bootstrap-resources.ps1"));
  const example = await fs.readFile(environmentExamplePath, "utf8");
  await fs.writeFile(
    path.join(fixture, ".env"),
    transformEnvironment(example),
    "utf8",
  );
  return fixture;
}

function runEnvironmentPreparation(fixture) {
  return spawnSync(
    "pwsh",
    [
      "-NoLogo",
      "-NoProfile",
      "-NonInteractive",
      "-File",
      path.join(fixture, "bootstrap-resources.ps1"),
      "-PrepareEnvironmentOnly",
    ],
    { encoding: "utf8", windowsHide: true },
  );
}

function runEnvironmentPreparationAsync(fixture) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "pwsh",
      [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        path.join(fixture, "bootstrap-resources.ps1"),
        "-PrepareEnvironmentOnly",
      ],
      { windowsHide: true },
    );
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", reject);
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

function normalizeProcessOutput(result) {
  return `${result.stdout}\n${result.stderr}`
    .replace(/\u001B\[[0-?]*[ -/]*[@-~]/gu, "")
    .replace(/\s+/gu, " ")
    .trim();
}

function readSingleEnvironmentValue(environment, name) {
  const prefix = `${name}=`;
  const values = environment
    .split(/\r?\n/u)
    .filter((line) => line.startsWith(prefix))
    .map((line) => line.slice(prefix.length));
  assert.equal(values.length, 1, `${name} must appear exactly once`);
  return values[0];
}

function assertCanonicalNacosToken(value) {
  assert.match(
    value,
    /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u,
  );
  const decoded = Buffer.from(value, "base64");
  assert.ok(decoded.length >= 32, "Nacos token must decode to at least 32 bytes");
  assert.equal(decoded.toString("base64"), value, "Nacos token must use canonical Base64");
}

async function readRuntimeNacosToken(fixture) {
  const runtimeEnvironment = await fs.readFile(
    path.join(fixture, ".runtime-secrets", "nacos-auth-token.env"),
    "utf8",
  );
  return readSingleEnvironmentValue(runtimeEnvironment, "NACOS_AUTH_TOKEN");
}

test("resource bootstrap waits for core middleware before initialization", async () => {
  const script = await fs.readFile(
    bootstrapPath,
    "utf8",
  );

  assert.match(script, /function Wait-CoreMiddleware/u);
  assert.match(script, /\/v3\/console\/health\/readiness/u);
  assert.match(script, /plainjournal-mysql/u);
  assert.match(script, /plainjournal-redis/u);
  assert.match(script, /-Port 18082/u);
  assert.match(script, /-Port 19000/u);

  const readinessCall = script.indexOf("\nWait-CoreMiddleware\n");
  const databaseInitialization = script.indexOf(
    "\n$mysqlRootPassword = Get-EnvValue",
  );
  assert.ok(
    readinessCall >= 0 && readinessCall < databaseInitialization,
    "Core middleware readiness must complete before database and Nacos initialization",
  );

  const compose = await fs.readFile(composePath, "utf8");
  assert.match(compose, /\.\/\.runtime-secrets\/nacos-auth-token\.env/u);
  assert.doesNotMatch(compose, /NACOS_AUTH_TOKEN:\s*\$\{/u);
});

test("fresh environment preparation creates one private canonical Nacos token and is idempotent", async () => {
  const fixture = await createBootstrapFixture();
  try {
    const example = await fs.readFile(environmentExamplePath, "utf8");
    assert.doesNotMatch(example, /^NACOS_AUTH_TOKEN=/mu);

    const first = runEnvironmentPreparation(fixture);
    assert.equal(first.status, 0, normalizeProcessOutput(first));
    const firstEnvironment = await fs.readFile(path.join(fixture, ".env"), "utf8");
    const token = readSingleEnvironmentValue(firstEnvironment, "NACOS_AUTH_TOKEN");
    assertCanonicalNacosToken(token);
    assert.equal(await readRuntimeNacosToken(fixture), token);
    assert.ok(!first.stdout.includes(token) && !first.stderr.includes(token));

    const second = runEnvironmentPreparation(fixture);
    assert.equal(second.status, 0, normalizeProcessOutput(second));
    const secondEnvironment = await fs.readFile(path.join(fixture, ".env"), "utf8");
    assert.equal(secondEnvironment, firstEnvironment);
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation preserves a valid custom Nacos token", async () => {
  const customToken = Buffer.alloc(32, 0x5a).toString("base64");
  const fixture = await createBootstrapFixture(
    (example) => `${example}\nexport NACOS_AUTH_TOKEN : '${customToken}' # operator value\n`,
  );
  try {
    const result = runEnvironmentPreparation(fixture);
    assert.equal(result.status, 0, normalizeProcessOutput(result));
    const environment = await fs.readFile(path.join(fixture, ".env"), "utf8");
    assert.match(environment, new RegExp(customToken, "u"));
    assert.equal(await readRuntimeNacosToken(fixture), customToken);
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation rejects interpolated Nacos tokens without interpreting Compose expressions", async () => {
  const referencedToken = Buffer.alloc(32, 0x5a).toString("base64");
  const fixture = await createBootstrapFixture(
    (example) =>
      `${example}\nNACOS_TOKEN=${referencedToken}\nNACOS_AUTH_TOKEN=\${NACOS_TOKEN}\n`,
  );
  try {
    const environmentPath = path.join(fixture, ".env");
    const before = await fs.readFile(environmentPath, "utf8");
    const result = runEnvironmentPreparation(fixture);
    assert.notEqual(result.status, 0);
    assert.match(
      normalizeProcessOutput(result),
      invalidNacosTokenDiagnostic,
    );
    assert.equal(await fs.readFile(environmentPath, "utf8"), before);
    await assert.rejects(
      fs.access(path.join(fixture, ".runtime-secrets", "nacos-auth-token.env")),
    );
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("failed revalidation invalidates a previously prepared Nacos runtime token", async () => {
  const fixture = await createBootstrapFixture();
  try {
    const first = runEnvironmentPreparation(fixture);
    assert.equal(first.status, 0, normalizeProcessOutput(first));
    await fs.access(
      path.join(fixture, ".runtime-secrets", "nacos-auth-token.env"),
    );

    const environmentPath = path.join(fixture, ".env");
    const environment = await fs.readFile(environmentPath, "utf8");
    await fs.writeFile(
      environmentPath,
      environment.replace(
        /^NACOS_AUTH_TOKEN=.*$/mu,
        "NACOS_AUTH_TOKEN=operator-chosen-but-invalid",
      ),
      "utf8",
    );

    const second = runEnvironmentPreparation(fixture);
    assert.notEqual(second.status, 0);
    assert.match(
      normalizeProcessOutput(second),
      invalidNacosTokenDiagnostic,
    );
    await assert.rejects(
      fs.access(path.join(fixture, ".runtime-secrets", "nacos-auth-token.env")),
    );
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation rejects export prefixes that Docker Compose cannot parse", async () => {
  const validToken = Buffer.alloc(32, 0x5a).toString("base64");
  for (const exportPrefix of ["EXPORT", "Export"]) {
    const fixture = await createBootstrapFixture(
      (example) => `${example}\n${exportPrefix} NACOS_AUTH_TOKEN=${validToken}\n`,
    );
    try {
      const environmentPath = path.join(fixture, ".env");
      const before = await fs.readFile(environmentPath, "utf8");
      const result = runEnvironmentPreparation(fixture);
      assert.notEqual(result.status, 0);
      assert.match(
        normalizeProcessOutput(result),
        /export prefix is case-sensitive and must be lowercase/u,
      );
      assert.equal(await fs.readFile(environmentPath, "utf8"), before);
      await assert.rejects(
        fs.access(path.join(fixture, ".runtime-secrets", "nacos-auth-token.env")),
      );
    } finally {
      await fs.rm(fixture, { recursive: true, force: true });
    }
  }
});

test("concurrent environment preparation cannot create duplicate Nacos tokens", async () => {
  const fixture = await createBootstrapFixture();
  try {
    const results = await Promise.all(
      Array.from({ length: 8 }, () => runEnvironmentPreparationAsync(fixture)),
    );
    assert.ok(results.some((result) => result.status === 0));
    for (const result of results.filter((candidate) => candidate.status !== 0)) {
      assert.match(
        normalizeProcessOutput(result),
        concurrentBootstrapDiagnostic,
      );
    }

    const environment = await fs.readFile(path.join(fixture, ".env"), "utf8");
    const token = readSingleEnvironmentValue(environment, "NACOS_AUTH_TOKEN");
    assertCanonicalNacosToken(token);
    assert.equal(await readRuntimeNacosToken(fixture), token);

    const final = runEnvironmentPreparation(fixture);
    assert.equal(final.status, 0, normalizeProcessOutput(final));
    assert.equal(
      readSingleEnvironmentValue(
        await fs.readFile(path.join(fixture, ".env"), "utf8"),
        "NACOS_AUTH_TOKEN",
      ),
      token,
    );
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation migrates only the repository's former Nacos placeholder", async () => {
  const fixture = await createBootstrapFixture(
    (example) => `${example}\nNACOS_AUTH_TOKEN=${legacyNacosTokenPlaceholder}\n`,
  );
  try {
    const result = runEnvironmentPreparation(fixture);
    assert.equal(result.status, 0, normalizeProcessOutput(result));
    const environment = await fs.readFile(path.join(fixture, ".env"), "utf8");
    const token = readSingleEnvironmentValue(environment, "NACOS_AUTH_TOKEN");
    assert.notEqual(token, legacyNacosTokenPlaceholder);
    assertCanonicalNacosToken(token);
    assert.equal(await readRuntimeNacosToken(fixture), token);
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation rejects semantic duplicate Nacos assignments before creating runtime secrets", async () => {
  const validToken = Buffer.alloc(48, 0x41).toString("base64");
  const fixture = await createBootstrapFixture(
    (example) => `${example}\nNACOS_AUTH_TOKEN=${validToken}\nNACOS_AUTH_TOKEN =operator-chosen-but-invalid\n`,
  );
  try {
    const environmentPath = path.join(fixture, ".env");
    const before = await fs.readFile(environmentPath, "utf8");
    const result = runEnvironmentPreparation(fixture);
    assert.notEqual(result.status, 0);
    assert.match(normalizeProcessOutput(result), /must appear exactly once/u);
    assert.equal(await fs.readFile(environmentPath, "utf8"), before);
    await assert.rejects(
      fs.access(path.join(fixture, ".runtime-secrets", "nacos-auth-token.env")),
    );
  } finally {
    await fs.rm(fixture, { recursive: true, force: true });
  }
});

test("environment preparation rejects malformed or undersized custom Nacos tokens without rewriting them", async () => {
  for (const invalidToken of [
    "operator-chosen-but-invalid",
    Buffer.from("too-short", "utf8").toString("base64"),
  ]) {
    const fixture = await createBootstrapFixture(
      (example) => `${example}\nNACOS_AUTH_TOKEN=${invalidToken}\n`,
    );
    try {
      const environmentPath = path.join(fixture, ".env");
      const before = await fs.readFile(environmentPath, "utf8");
      const result = runEnvironmentPreparation(fixture);
      assert.notEqual(result.status, 0);
      assert.match(
        normalizeProcessOutput(result),
        invalidNacosTokenDiagnostic,
      );
      assert.equal(await fs.readFile(environmentPath, "utf8"), before);
    } finally {
      await fs.rm(fixture, { recursive: true, force: true });
    }
  }
});
