import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  coveragePercentage,
  parseJacocoCsv,
  validateBackendCoverage,
} from "./check-backend-coverage.mjs";

describe("backend coverage gate", () => {
  it("adds JaCoCo line counters across classes", () => {
    const counters = parseJacocoCsv([
      "GROUP,PACKAGE,CLASS,LINE_MISSED,LINE_COVERED",
      "plain,one,First,10,30",
      "plain,two,Second,5,55",
    ].join("\n"));

    assert.deepEqual(counters, { missed: 15, covered: 85 });
    assert.equal(coveragePercentage(counters), 85);
  });

  it("enforces module and weighted aggregate thresholds", () => {
    const result = validateBackendCoverage([
      { name: "small", missed: 2, covered: 8, percentage: 80 },
      { name: "large", missed: 400, covered: 600, percentage: 60 },
    ]);

    assert.equal(result.aggregate.percentage, 60.2);
    assert.equal(result.failures.length, 2);
  });
});
