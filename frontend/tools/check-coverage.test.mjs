import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  summarizeCoverage,
  validateCoverage,
} from "./check-coverage.mjs";

describe("frontend coverage gate", () => {
  it("uses source line counts instead of averaging package percentages", () => {
    assert.deepEqual(summarizeCoverage([
      { covered: 90, total: 100 },
      { covered: 10, total: 1000 },
    ]), {
      covered: 100,
      total: 1100,
      percentage: 9.09,
    });
  });

  it("fails both package and aggregate regressions", () => {
    const result = validateCoverage([
      {
        name: "admin",
        covered: 59,
        total: 100,
        percentage: 59,
        minimumLines: 60,
      },
      {
        name: "storefront",
        covered: 70,
        total: 100,
        percentage: 70,
        minimumLines: 70,
      },
    ]);

    assert.equal(result.aggregate.percentage, 64.5);
    assert.equal(result.failures.length, 2);
  });
});
