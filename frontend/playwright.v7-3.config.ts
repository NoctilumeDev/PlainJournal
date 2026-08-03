import { defineConfig } from "@playwright/test";

import { chromiumLaunchOptions } from "./playwright.shared";


export default defineConfig({
  testDir: "./e2e/specs",
  testMatch: "v7-3.spec.ts",
  outputDir: "./test-results/v7-3",
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: {
    timeout: 8_000,
  },
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:18300",
    browserName: "chromium",
    headless: true,
    launchOptions: chromiumLaunchOptions(),
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
});
