import assert from "node:assert/strict";
import test from "node:test";

import { renderVerificationSummary } from "./render-verification-summary.mjs";

test("renders current verification counts", () => {
  const output = renderVerificationSummary({
    targetRelease: "v1.0.2",
    status: "release-candidate",
    verifiedOn: "2026-08-04",
    backend: {
      surefireReports: 1,
      tests: 2,
      failures: 0,
      errors: 0,
      skipped: 0,
      pmdReports: 1,
      pmdViolations: 0,
      spotbugsReports: 1,
      spotbugsPriority1: 0,
      spotbugsPriority2: 3,
      spotbugsPriority3: 4,
      lineCoverage: 72.43,
      lineCoverageMinimum: 70,
    },
    frontend: {
      unitAndContractTests: 5,
      developmentE2E: 6,
      productionE2E: 7,
      layerRules: 8,
      deliveryRules: 9,
      deploymentRules: 10,
      releaseMaterialRules: 11,
      lineCoverage: 70.06,
      lineCoverageMinimum: 70,
    },
    realEvidence: {
      coreMiddleware: 7,
      representativeInstances: 3,
      capacityRequests: 1000,
      capacityConcurrency: 100,
      browserConsoleErrors: 0,
      browserNetworkErrors: 0,
    },
  });

  assert.match(output, /2 tests/u);
  assert.match(output, /5 \/ 5/u);
  assert.match(output, /72\.43%/u);
  assert.match(output, /70\.06%/u);
  assert.match(output, /1000 请求 \/ 100 并发/u);
});
