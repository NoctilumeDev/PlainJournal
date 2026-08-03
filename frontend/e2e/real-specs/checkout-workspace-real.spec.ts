import fs from "node:fs";
import path from "node:path";

import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const email = requiredEnvironment("PLAIN_JOURNAL_REAL_CHECKOUT_EMAIL");
const password = requiredEnvironment("PLAIN_JOURNAL_REAL_CHECKOUT_PASSWORD");
const productId = requiredEnvironment("PLAIN_JOURNAL_REAL_CHECKOUT_PRODUCT_ID");
const skuId = requiredEnvironment("PLAIN_JOURNAL_REAL_CHECKOUT_SKU_ID");
const productTitle = requiredEnvironment("PLAIN_JOURNAL_REAL_CHECKOUT_PRODUCT_TITLE");
const evidencePath = process.env.PLAIN_JOURNAL_REAL_CHECKOUT_EVIDENCE?.trim();

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is required for the real checkout test.`);
  }
  return value;
}

async function login(page: Page) {
  await page.goto("/login?returnTo=%2Fcheckout");
  await page.getByLabel("邮箱").fill(email);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL("/checkout");
}

async function accessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  return result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
}

test("real Gateway checkout rechecks owner-scoped facts without hiding price changes", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const failedRequests: Array<{ method: string; path: string }> = [];
  const responses: Array<{ method: string; path: string; status: number }> = [];
  const previewBodies: Array<Record<string, unknown>> = [];
  const relevantPrefixes = [
    "/api/v1/identity/addresses",
    "/api/v1/trade/cart/items",
    "/api/v1/marketing/",
    `/api/v1/catalog/products/${productId}`,
    `/api/v1/inventory/stocks/${skuId}`,
  ];

  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/v1/marketing/pricing-previews") {
      previewBodies.push(request.postDataJSON() as Record<string, unknown>);
    }
  });
  page.on("requestfailed", (request) => {
    const url = new URL(request.url());
    if (relevantPrefixes.some((prefix) => url.pathname.startsWith(prefix))) {
      failedRequests.push({
        method: request.method(),
        path: url.pathname,
      });
    }
  });
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (relevantPrefixes.some((prefix) => url.pathname.startsWith(prefix))) {
      responses.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });

  await login(page);
  await expect(page.getByRole("heading", { name: "订单确认", level: 1 }))
    .toBeVisible();
  const line = page.getByRole("article").filter({ hasText: productTitle });
  await expect(line).toContainText("2 件");
  await expect(line).toContainText("¥378.00");

  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  await expect(page.getByText("权威核对已完成")).toBeVisible();
  await expect(line).toContainText("实时单价 ¥199.00");
  await expect(line).toContainText("可用 3 件");
  await expect(line).toContainText("价格已变化");
  await expect(line).toContainText("¥398.00");
  await expect(page.getByRole("button", { name: "以当前事实提交订单 →" }))
    .toBeEnabled();

  const desktopViolations = await accessibilityViolations(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("complementary", { name: "金额明细" })).toBeVisible();
  const mobileLayout = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
  }));
  const mobileViolations = await accessibilityViolations(page);

  expect(previewBodies).toHaveLength(2);
  expect(previewBodies[0]).toMatchObject({
    originalAmount: "378.00",
    benefitNos: [],
  });
  expect(previewBodies[1]).toMatchObject({
    originalAmount: "398.00",
    benefitNos: [],
  });
  expect(responses).toEqual(expect.arrayContaining([
    { method: "GET", path: "/api/v1/identity/addresses", status: 200 },
    { method: "GET", path: "/api/v1/trade/cart/items", status: 200 },
    { method: "GET", path: "/api/v1/marketing/benefits", status: 200 },
    {
      method: "GET",
      path: `/api/v1/catalog/products/${productId}`,
      status: 200,
    },
    {
      method: "GET",
      path: `/api/v1/inventory/stocks/${skuId}`,
      status: 200,
    },
  ]));
  expect(responses.every((response) => response.status === 200)).toBe(true);
  expect(failedRequests).toEqual([]);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([]);
  expect(desktopViolations).toEqual([]);
  expect(mobileViolations).toEqual([]);
  expect(mobileLayout.scrollWidth).toBe(mobileLayout.clientWidth);
  expect(mobileLayout.bodyScrollWidth).toBe(mobileLayout.clientWidth);

  const evidence = {
    schemaVersion: 1,
    productId,
    skuId,
    productTitle,
    cartSnapshotAmount: "378.00",
    authoritativeAmount: "398.00",
    authoritativeAvailable: 3,
    previewBodies,
    responses,
    failedRequests,
    pageErrors,
    consoleErrors,
    desktopSeriousOrCriticalViolations: desktopViolations.length,
    mobileSeriousOrCriticalViolations: mobileViolations.length,
    mobileLayout,
  };
  if (evidencePath) {
    fs.mkdirSync(path.dirname(evidencePath), { recursive: true });
    fs.writeFileSync(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  console.log(`REAL_CHECKOUT_EVIDENCE=${JSON.stringify(evidence)}`);
});
