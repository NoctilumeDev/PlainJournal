import path from "node:path";

import {
  frontendRoot,
  runPnpm,
  startFixture,
  stopFixture,
} from "./fixture-runtime.mjs";

const statePath = path.join(frontendRoot, ".run", `production-e2e-${process.pid}.json`);

try {
  await startFixture({
    mode: "production",
    statePath,
    skipBuild: process.argv.includes("--skip-build"),
    logPrefix: "production-e2e",
  });
  runPnpm(["exec", "playwright", "test", "--config=playwright.v7-3.config.ts"]);
} finally {
  await stopFixture(statePath);
}
