import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { inspectReleaseReadiness } from "./release-readiness.mjs";

const frontendRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

test("keeps GitHub release materials complete", async () => {
  const result = await inspectReleaseReadiness(frontendRoot);
  const baseline = JSON.parse(
    await fs.readFile(result.paths.verificationBaseline, "utf8"),
  );
  assert.deepEqual(result.violations, []);
  assert.equal(result.screenshots.length, 3);
  assert.equal(
    path.basename(result.paths.releaseNotes),
    `${baseline.targetRelease}.md`,
  );
  assert.match(result.releaseDate, /^\d{4}-\d{2}-\d{2}$/u);
});

test("keeps the Apache-2.0 license explicit and complete", async () => {
  const result = await inspectReleaseReadiness(frontendRoot);
  assert.equal(result.licensePresent, true);
  assert.equal(result.licenseId, "Apache-2.0");
});

test("keeps release artifacts inside the repository root", async () => {
  const result = await inspectReleaseReadiness(frontendRoot);
  const repositoryRoot = path.resolve(frontendRoot, "..");
  for (const releasePath of Object.values(result.paths)) {
    assert.ok(releasePath.startsWith(repositoryRoot));
  }
});
