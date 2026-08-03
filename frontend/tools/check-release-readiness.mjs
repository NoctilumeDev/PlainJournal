import path from "node:path";
import { fileURLToPath } from "node:url";

import { inspectReleaseReadiness } from "./release-readiness.mjs";

const frontendRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const result = await inspectReleaseReadiness(frontendRoot);

if (result.violations.length > 0) {
  console.error("Frontend release readiness violations:");
  for (const violation of result.violations) {
    console.error(`- ${violation}`);
  }
  process.exitCode = 1;
} else {
  console.log(
    "Frontend release readiness passed: "
    + `${result.screenshots.length} screenshots, README, CHANGELOG, `
    + "SECURITY, Apache-2.0 LICENSE and the manual release checklist verified.",
  );
}
