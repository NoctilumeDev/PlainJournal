import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

test("resource bootstrap waits for core middleware before initialization", async () => {
  const script = await fs.readFile(
    path.join(repositoryRoot, "deploy", "docker", "bootstrap-resources.ps1"),
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
});
