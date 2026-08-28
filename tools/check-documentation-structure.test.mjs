import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { inspectDocumentationStructure } from "./check-documentation-structure.mjs";

async function createFixture() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "plainjournal-docs-"));
  const docs = path.join(root, "docs");
  await fs.mkdir(path.join(docs, "quality"), { recursive: true });
  await fs.mkdir(path.join(docs, "evidence"), { recursive: true });

  const currentFiles = [
    "00-project-master-plan.md",
    "01-product-scope.md",
    "02-service-architecture.md",
    "03-core-state-machines.md",
    "04-data-ownership.md",
    "05-consistency-strategy.md",
    "06-version-matrix.md",
    "07-local-development-network.md",
    "08-identity-security.md",
    "09-redis-traffic-protection.md",
    "10-catalog-service.md",
    "11-inventory-service.md",
    "12-trade-service.md",
    "13-payment-service.md",
    "14-fulfillment-service.md",
    "15-marketing-service.md",
    "16-after-sale-refund.md",
    "17-technology-adoption-matrix.md",
    "18-observability-and-alerting.md",
    "19-compensation-governance.md",
    "20-payment-reconciliation.md",
    "21-inventory-reconciliation.md",
    "22-synchronous-call-resilience.md",
    "23-trade-scheduling-isolation.md",
    "24-distributed-tracing.md",
    "25-trade-fulfillment-reconciliation.md",
    "32gib-extended-validation-runbook.md",
    "core-smoke.md",
    "project-history.md",
    "reference-baseline-and-pro-boundary.md",
    "verification-index.md",
    "verification-summary.md",
  ];
  for (const name of currentFiles) {
    await fs.writeFile(path.join(docs, name), `# ${name}\n`, "utf8");
  }
  await fs.writeFile(
    path.join(docs, "README.md"),
    [
      "# Docs",
      ...currentFiles.map((name) => `- [${name}](${name})`),
      "",
    ].join("\n"),
    "utf8",
  );
  await fs.writeFile(path.join(docs, "quality", "spotbugs-triage.md"), "# SpotBugs\n", "utf8");

  const evidenceFiles = [
    "m0-m8-three-layer-acceptance-20260728.md",
    "v1.0.2-engineering-acceptance-20260804.md",
  ];
  for (const name of evidenceFiles) {
    await fs.writeFile(path.join(docs, "evidence", name), `# ${name}\n`, "utf8");
  }
  await fs.writeFile(
    path.join(docs, "evidence", "README.md"),
    ["# Evidence", ...evidenceFiles.map((name) => `- [${name}](${name})`), ""].join("\n"),
    "utf8",
  );

  return root;
}

test("accepts current specifications and the compact evidence set", async (context) => {
  const root = await createFixture();
  context.after(() => fs.rm(root, { recursive: true, force: true }));

  const result = await inspectDocumentationStructure(root);

  assert.deepEqual(result.violations, []);
});

test("rejects machine paths, mutable counts, and misplaced numbered documents", async (context) => {
  const root = await createFixture();
  context.after(() => fs.rm(root, { recursive: true, force: true }));
  const docs = path.join(root, "docs");
  await fs.writeFile(
    path.join(docs, "08-identity-security.md"),
    "# Broken\n\nC:\\Users\\example\\repo\n\n130 tests\n",
    "utf8",
  );
  await fs.writeFile(path.join(docs, "26-misplaced.md"), "# Misplaced\n", "utf8");

  const result = await inspectDocumentationStructure(root);

  assert.ok(result.violations.some((violation) => violation.includes("absolute path")));
  assert.ok(result.violations.some((violation) => violation.includes("mutable test counts")));
  assert.ok(result.violations.some((violation) => violation.includes("unexpected document number 26")));
});

test("rejects stage-log archives and unapproved evidence snapshots", async (context) => {
  const root = await createFixture();
  context.after(() => fs.rm(root, { recursive: true, force: true }));
  const docs = path.join(root, "docs");
  await fs.mkdir(path.join(docs, "archive"), { recursive: true });
  await fs.writeFile(path.join(docs, "archive", "stage-log.md"), "# Stage log\n", "utf8");
  await fs.writeFile(path.join(docs, "evidence", "extra.md"), "# Extra\n", "utf8");

  const result = await inspectDocumentationStructure(root);

  assert.ok(result.violations.some((violation) => violation.includes("stage logs")));
  assert.ok(result.violations.some((violation) => violation.includes("must contain only")));
});

test("rejects stale stage-log references outside docs", async (context) => {
  const root = await createFixture();
  context.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.writeFile(
    path.join(root, "README.md"),
    "# Root\n\nSee `docs/41-old-stage-report.md`.\n",
    "utf8",
  );

  const result = await inspectDocumentationStructure(root);

  assert.ok(
    result.violations.some((violation) => violation.includes("removed stage-log document")),
  );
});
