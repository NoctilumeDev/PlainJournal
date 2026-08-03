import path from "node:path";
import { fileURLToPath } from "node:url";

import { inspectProductionDeployment } from "./production-deployment.mjs";

const frontendRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const result = await inspectProductionDeployment(frontendRoot);

if (result.violations.length > 0) {
  console.error("Frontend production deployment violations:");
  for (const violation of result.violations) {
    console.error(`- ${violation}`);
  }
  process.exitCode = 1;
} else {
  console.log(
    "Frontend production deployment passed: "
    + "History fallback, cache policy, Gateway/WebSocket proxy, "
    + "two image targets and fixture-only demo accounts verified.",
  );
}
