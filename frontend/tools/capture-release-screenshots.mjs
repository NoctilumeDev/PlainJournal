import fs from "node:fs/promises";
import path from "node:path";

import { chromium } from "@playwright/test";

const frontendRoot = path.resolve(import.meta.dirname, "..");
const repositoryRoot = path.resolve(frontendRoot, "..");
const outputRoot = path.join(repositoryRoot, "docs", "assets", "v7-4");
const chromeExecutable = process.env.PLAYWRIGHT_CHROME_EXECUTABLE
  ?? path.join(
    process.env.LOCALAPPDATA ?? "",
    "Google",
    "Chrome",
    "Application",
    "chrome.exe",
  );

await fs.mkdir(outputRoot, { recursive: true });

const browser = await chromium.launch({
  executablePath: chromeExecutable,
  headless: true,
});

try {
  const context = await browser.newContext({
    viewport: {
      width: 1440,
      height: 960,
    },
    deviceScaleFactor: 1,
    colorScheme: "light",
    reducedMotion: "reduce",
  });
  const page = await context.newPage();

  await page.goto("http://127.0.0.1:18300/", { waitUntil: "networkidle" });
  await page.screenshot({
    path: path.join(outputRoot, "storefront-home.jpg"),
    type: "jpeg",
    quality: 88,
  });

  await page.goto(
    "http://127.0.0.1:18300/products/2079000000000000001",
    { waitUntil: "networkidle" },
  );
  await page.getByRole("heading", { name: "帆布通勤袋", level: 1 }).waitFor();
  await page.screenshot({
    path: path.join(outputRoot, "storefront-product.jpg"),
    type: "jpeg",
    quality: 88,
  });

  await page.goto("http://127.0.0.1:18301/governance");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await page.getByRole("heading", { name: "补偿与对账", level: 1 }).waitFor();
  await page.screenshot({
    path: path.join(outputRoot, "admin-governance.jpg"),
    type: "jpeg",
    quality: 88,
  });

  console.log(`Release screenshots written to ${outputRoot}`);
} finally {
  await browser.close();
}
