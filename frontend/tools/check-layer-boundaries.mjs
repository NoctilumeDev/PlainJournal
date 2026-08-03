import path from "node:path";
import { fileURLToPath } from "node:url";

import { inspectFrontendBoundaries } from "./layer-boundaries.mjs";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const result = await inspectFrontendBoundaries(frontendRoot);

if (result.violations.length > 0) {
  console.error("Frontend layer boundary violations:");
  for (const violation of result.violations) {
    console.error(`- ${violation}`);
  }
  process.exitCode = 1;
} else {
  console.log(
    `Frontend layer boundaries passed: ${result.layeredFiles} layered files, `
    + `${result.checkedImports} relative imports, design-system ownership verified.`,
  );
}
