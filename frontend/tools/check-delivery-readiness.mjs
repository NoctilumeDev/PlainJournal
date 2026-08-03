import path from "node:path";
import { fileURLToPath } from "node:url";

import { inspectDeliveryReadiness } from "./delivery-readiness.mjs";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const result = await inspectDeliveryReadiness(frontendRoot);

if (result.violations.length > 0) {
  console.error("Frontend delivery readiness violations:");
  for (const violation of result.violations) {
    console.error(`- ${violation}`);
  }
  process.exitCode = 1;
} else {
  console.log(
    `Frontend delivery readiness passed: `
    + `${result.routePaths.storefront.length} storefront routes, `
    + `${result.routePaths.admin.length} admin routes, `
    + `${result.productionVueFiles} production Vue files, `
    + `${result.originalAssets.length} original images, `
    + `${result.optimizedAssets.length} responsive variants.`,
  );
}

if (result.warnings.length > 0) {
  console.warn("V7 delivery warnings:");
  for (const warning of result.warnings) {
    console.warn(`- ${warning}`);
  }
}
