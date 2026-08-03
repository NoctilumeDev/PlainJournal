import { defineConfig } from "@playwright/test";

import { chromiumLaunchOptions } from "./playwright.shared";


export default defineConfig({
  testDir: "./e2e/real-specs",
  testMatch: "checkout-workspace-real.spec.ts",
  outputDir: "./test-results/real-checkout",
  fullyParallel: false,
  workers: 1,
  timeout: 45_000,
  expect: {
    timeout: 10_000,
  },
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:18200",
    browserName: "chromium",
    headless: true,
    launchOptions: chromiumLaunchOptions(),
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "pnpm dev:storefront",
    url: "http://127.0.0.1:18200/login",
    env: {
      ...process.env,
      VITE_API_PROXY_TARGET: "http://127.0.0.1:18000",
    },
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
