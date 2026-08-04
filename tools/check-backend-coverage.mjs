import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const backendCoverageModules = [
  "platform-common",
  "ecommerce-gateway",
  "services/identity-service",
  "services/catalog-service",
  "services/inventory-service",
  "services/trade-service",
  "services/payment-service",
  "services/fulfillment-service",
  "services/marketing-service",
  "services/chat-service",
  "services/notification-service",
  "services/analytics-service",
];

export function parseJacocoCsv(csv) {
  const [headerLine, ...lines] = csv.trim().split(/\r?\n/u);
  if (!headerLine) {
    throw new Error("JaCoCo CSV is empty");
  }
  const headers = headerLine.split(",");
  const missedIndex = headers.indexOf("LINE_MISSED");
  const coveredIndex = headers.indexOf("LINE_COVERED");
  if (missedIndex < 0 || coveredIndex < 0) {
    throw new Error("JaCoCo CSV is missing line counters");
  }
  return lines.reduce(
    (summary, line) => {
      const columns = line.split(",");
      return {
        missed: summary.missed + Number(columns[missedIndex]),
        covered: summary.covered + Number(columns[coveredIndex]),
      };
    },
    { missed: 0, covered: 0 },
  );
}

export function coveragePercentage({ missed, covered }) {
  const total = missed + covered;
  return total === 0
    ? 0
    : Number(((covered / total) * 100).toFixed(2));
}

export function validateBackendCoverage(
  modules,
  moduleMinimum = 65,
  aggregateMinimum = 70,
) {
  const failures = modules
    .filter((module) => module.percentage < moduleMinimum)
    .map((module) =>
      `${module.name} line coverage ${module.percentage}% is below ${moduleMinimum}%`);
  const aggregate = modules.reduce(
    (summary, module) => ({
      missed: summary.missed + module.missed,
      covered: summary.covered + module.covered,
    }),
    { missed: 0, covered: 0 },
  );
  const percentage = coveragePercentage(aggregate);
  if (percentage < aggregateMinimum) {
    failures.push(
      `aggregate line coverage ${percentage}% is below ${aggregateMinimum}%`,
    );
  }
  return { aggregate: { ...aggregate, percentage }, failures };
}

async function main() {
  const repositoryRoot = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
  );
  const backendRoot = path.join(repositoryRoot, "backend");
  const modules = [];
  for (const name of backendCoverageModules) {
    const reportPath = path.join(
      backendRoot,
      name,
      "target",
      "site",
      "jacoco",
      "jacoco.csv",
    );
    const counters = parseJacocoCsv(await fs.readFile(reportPath, "utf8"));
    modules.push({
      name,
      ...counters,
      percentage: coveragePercentage(counters),
    });
  }
  const result = validateBackendCoverage(modules);
  for (const module of modules) {
    console.log(
      `${module.name}: ${module.percentage}% `
        + `(${module.covered}/${module.covered + module.missed} lines)`,
    );
  }
  console.log(
    `aggregate: ${result.aggregate.percentage}% `
      + `(${result.aggregate.covered}/`
      + `${result.aggregate.covered + result.aggregate.missed} lines)`,
  );
  if (result.failures.length > 0) {
    throw new Error(result.failures.join("\n"));
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
