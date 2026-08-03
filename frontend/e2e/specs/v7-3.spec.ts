import { expect, test, type Page } from "@playwright/test";

const productId = "2079000000000000001";
const completedOrderNo = "ORD2079000000000007001";

function observePage(page: Page) {
  const errors: string[] = [];
  const warnings: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      errors.push(message.text());
    } else if (message.type() === "warning") {
      warnings.push(message.text());
    }
  });
  return { errors, warnings };
}

test.describe.configure({ mode: "serial" });

test("V7.3 production storefront refreshes deep routes and keeps API same-origin", async ({
  page,
}) => {
  const diagnostics = observePage(page);
  const apiResponses: Array<{ origin: string; status: number }> = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/")) {
      apiResponses.push({
        origin: url.origin,
        status: response.status(),
      });
    }
  });

  const response = await page.goto(`/products/${productId}`);
  expect(response?.status()).toBe(200);
  expect(response?.headers()["content-type"]).toContain("text/html");
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();

  const entryScript = await page.locator('script[type="module"]').getAttribute("src");
  expect(entryScript).toMatch(/^\/assets\/.+\.js$/u);
  const image = page.locator(".product-media__main img");
  await expect.poll(() => image.evaluate((element) =>
    element.complete && element.naturalWidth > 0)).toBe(true);
  expect(new URL(await image.evaluate((element) => element.currentSrc)).pathname)
    .toMatch(/canvas-commuter-tote-(?:480|800|1122)\.avif$/u);

  expect(apiResponses.length).toBeGreaterThan(0);
  expect(apiResponses.every((item) =>
    item.origin === "http://127.0.0.1:18300" && item.status === 200)).toBe(true);

  await page.reload();
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();
  expect(diagnostics.errors).toEqual([]);
  expect(diagnostics.warnings).toEqual([]);
});

test("V7.3 fixture rejects wrong credentials and preserves an authenticated deep refresh", async ({
  page,
}) => {
  const diagnostics = observePage(page);
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("WrongPass123");
  const rejectedLogin = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/v1/identity/auth/login");
  await page.getByRole("button", { name: "登录 →" }).click();
  expect((await rejectedLogin).status()).toBe(401);
  await expect(page).toHaveURL(/\/login$/u);

  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);

  await page.goto(`/orders/${completedOrderNo}`);
  await expect(page.getByRole("heading", { name: "订单详情", level: 1 }))
    .toBeVisible();
  await page.reload();
  await expect(page.getByText(`订单 ${completedOrderNo}`)).toBeVisible();

  expect(diagnostics.errors.filter((message) =>
    !message.includes("401 (Unauthorized)"))).toEqual([]);
  expect(diagnostics.warnings).toEqual([]);
});

test("V7.3 production admin restores the guarded governance route", async ({
  page,
}) => {
  const diagnostics = observePage(page);
  const apiOrigins: string[] = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/")) {
      apiOrigins.push(url.origin);
    }
  });

  await page.goto("http://127.0.0.1:18301/governance");
  await expect(page).toHaveURL((url) =>
    url.origin === "http://127.0.0.1:18301"
    && url.pathname === "/login"
    && url.searchParams.get("redirect") === "/governance");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();

  await expect(page).toHaveURL(/\/governance$/u);
  await expect(page.getByRole("heading", { name: "补偿与对账", level: 1 }))
    .toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "补偿与对账", level: 1 }))
    .toBeVisible();

  expect(apiOrigins.length).toBeGreaterThan(0);
  expect(apiOrigins.every((origin) => origin === "http://127.0.0.1:18301"))
    .toBe(true);
  expect(diagnostics.errors).toEqual([]);
  expect(diagnostics.warnings).toEqual([]);
});
