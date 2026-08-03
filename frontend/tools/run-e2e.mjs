import path from "node:path";

import {
  frontendRoot,
  runPnpm,
  startFixture,
  stopFixture,
} from "./fixture-runtime.mjs";

function optionValue(name, fallback) {
  const prefix = `${name}=`;
  const argument = process.argv.find((value) => value.startsWith(prefix));
  return argument ? argument.slice(prefix.length) : fallback;
}

const storefrontOnly = process.argv.includes("--storefront-only");
const adminOnly = process.argv.includes("--admin-only");
if (storefrontOnly && adminOnly) {
  throw new Error("--storefront-only and --admin-only cannot be combined");
}

const config = optionValue("--config", "playwright.config.ts");
const statePath = path.join(frontendRoot, ".run", `e2e-${process.pid}.json`);

try {
  await startFixture({
    mode: "development",
    statePath,
    storefront: !adminOnly,
    admin: !storefrontOnly,
    logPrefix: `e2e-${path.basename(config, path.extname(config))}`,
  });
  runPnpm(["exec", "playwright", "test", `--config=${config}`]);
} finally {
  await stopFixture(statePath);
}
