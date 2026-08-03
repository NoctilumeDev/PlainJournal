import path from "node:path";

import { defineConfig } from "@playwright/test";

const chromeExecutable = process.env.PLAYWRIGHT_CHROME_EXECUTABLE
  ?? path.join(
    process.env.LOCALAPPDATA ?? "",
    "Google",
    "Chrome",
    "Application",
    "chrome.exe",
  );

export default defineConfig({
  testDir: "./e2e/specs",
  testMatch: "v5-3.spec.ts",
  outputDir: "./test-results/v5-3",
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: {
    timeout: 8_000,
  },
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:18200",
    browserName: "chromium",
    headless: true,
    launchOptions: {
      executablePath: chromeExecutable,
    },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
});
