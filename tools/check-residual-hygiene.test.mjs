import assert from "node:assert/strict";
import test from "node:test";

import { inspectResidualHygiene } from "./check-residual-hygiene.mjs";

test("accepts current source, product assets, and bounded final evidence", () => {
  const result = inspectResidualHygiene({
    trackedPaths: [
      "backend/catalog-service/src/main/java/example/Catalog.java",
      "docs/assets/showcase/v1.1.0/storefront-product-detail.png",
      "docs/evidence/v1.0.2-engineering-acceptance-20260804.md",
      "tools/check-residual-hygiene.mjs",
    ],
  });

  assert.deepEqual(result.violations, []);
});

test("rejects tracked ignored build output", () => {
  const result = inspectResidualHygiene({
    trackedPaths: ["backend/catalog-service/target/catalog-service.jar"],
    ignoredTrackedPaths: ["backend/catalog-service/target/catalog-service.jar"],
  });

  assert.deepEqual(
    result.violations.map(({ code }) => code),
    ["TRACKED_IGNORED_ARTIFACT"],
  );
});

test("rejects historical construction media from the current checkout", () => {
  const result = inspectResidualHygiene({
    trackedPaths: [
      "history/worklogs/design-qa.md",
      "history/worklogs/2026-09-01/example.png",
    ],
  });

  assert.deepEqual(
    result.violations.map(({ code, path }) => ({ code, path })),
    [{
      code: "HISTORICAL_CONSTRUCTION_ASSET",
      path: "history/worklogs/2026-09-01/example.png",
    }],
  );
});

test("rejects temporary and conflict-backup files", () => {
  const result = inspectResidualHygiene({
    trackedPaths: ["docs/README.md.orig", "notes.tmp", "source.java~"],
  });

  assert.deepEqual(
    result.violations.map(({ code }) => code),
    [
      "TRACKED_TEMPORARY_FILE",
      "TRACKED_TEMPORARY_FILE",
      "TRACKED_TEMPORARY_FILE",
    ],
  );
});
