import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const coveragePackages = [
  { name: "foundation", minimumLines: 70 },
  { name: "ui", minimumLines: 90 },
  { name: "admin", minimumLines: 60 },
  { name: "storefront", minimumLines: 70 },
];

export function summarizeCoverage(entries) {
  const totals = entries.reduce(
    (summary, entry) => ({
      covered: summary.covered + entry.covered,
      total: summary.total + entry.total,
    }),
    { covered: 0, total: 0 },
  );
  return {
    ...totals,
    percentage: totals.total === 0
      ? 0
      : Number(((totals.covered / totals.total) * 100).toFixed(2)),
  };
}

export function validateCoverage(entries, aggregateMinimum = 70) {
  const failures = [];
  for (const entry of entries) {
    if (entry.percentage < entry.minimumLines) {
      failures.push(
        `${entry.name} line coverage ${entry.percentage}% is below ${entry.minimumLines}%`,
      );
    }
  }
  const aggregate = summarizeCoverage(entries);
  if (aggregate.percentage < aggregateMinimum) {
    failures.push(
      `aggregate line coverage ${aggregate.percentage}% is below ${aggregateMinimum}%`,
    );
  }
  return { aggregate, failures };
}

async function main() {
  const frontendRoot = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
  );
  const entries = [];
  for (const definition of coveragePackages) {
    const summaryPath = path.join(
      frontendRoot,
      "coverage",
      definition.name,
      "coverage-summary.json",
    );
    const summary = JSON.parse(await fs.readFile(summaryPath, "utf8"));
    const lines = summary.total?.lines;
    if (
      !lines
      || !Number.isInteger(lines.covered)
      || !Number.isInteger(lines.total)
      || typeof lines.pct !== "number"
    ) {
      throw new Error(`Invalid coverage summary: ${summaryPath}`);
    }
    entries.push({
      ...definition,
      covered: lines.covered,
      total: lines.total,
      percentage: lines.pct,
    });
  }

  const result = validateCoverage(entries);
  for (const entry of entries) {
    console.log(
      `${entry.name}: ${entry.percentage}% (${entry.covered}/${entry.total} lines)`,
    );
  }
  console.log(
    `aggregate: ${result.aggregate.percentage}% `
      + `(${result.aggregate.covered}/${result.aggregate.total} lines)`,
  );
  if (result.failures.length > 0) {
    throw new Error(result.failures.join("\n"));
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
