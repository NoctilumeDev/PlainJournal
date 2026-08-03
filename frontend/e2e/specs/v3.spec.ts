import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const pendingOrderNo = "ORD2079000000000008001";
const completedOrderNo = "ORD2079000000000007001";
const paymentExceptionOrderNo = "ORD2079000000000009001";
const productId = "2079000000000000001";

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

async function expectNoRootOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBe(dimensions.clientWidth);
}

async function expectLoadedImages(page: Page, selector: string) {
  const images = page.locator(selector);
  expect(await images.count()).toBeGreaterThan(0);
  await expect.poll(() => images.evaluateAll((values) =>
    values.every((value) =>
      value instanceof HTMLImageElement
      && value.complete
      && value.naturalWidth > 0))).toBe(true);
}

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

function observeDiagnostics(page: Page) {
  const pageErrors: string[] = [];
  const consoleWarnings: string[] = [];
  const consoleErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "warning") {
      consoleWarnings.push(message.text());
    }
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  return { pageErrors, consoleWarnings, consoleErrors };
}

test.describe.configure({ mode: "serial" });

test("V3 home prototype keeps one quiet hierarchy with real images at desktop and 320px", async ({ page }) => {
  const diagnostics = observeDiagnostics(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");

  await expect(page.getByRole("heading", {
    name: "真正值得使用的东西，不需要促销噪音。",
    level: 1,
  })).toBeVisible();
  await expect(page.locator("main")).toHaveCount(1);
  await expect(page.locator("h1")).toHaveCount(1);
  await expectLoadedImages(page, ".home-featured img, .product-card__media img");
  await expectNoRootOverflow(page);

  const heroHeight = await page.locator(".home-hero").evaluate((element) =>
    element.getBoundingClientRect().height);
  expect(heroHeight).toBeLessThanOrEqual(720);

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expect(page.locator("h1")).toHaveCount(1);
  await expectNoSeriousAccessibilityViolations(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test("V3 product prototype preserves facts, bag action and both themes at 390px", async ({ page }) => {
  const diagnostics = observeDiagnostics(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/products/${productId}`);

  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();
  await expect(page.locator("h1")).toHaveCount(1);
  await expectLoadedImages(page, ".product-media__main img");
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");
  await expectNoRootOverflow(page);

  await page.getByRole("button", { name: "加入购物袋" }).click();
  await expect(page.getByText("已放入购物袋", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看购物袋" })).toBeVisible();

  await page.goto("/index");
  await page.getByLabel("素白").check();
  await page.goto(`/products/${productId}`);
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test("V3 order prototype keeps pending, completed and payment exception facts distinct", async ({ page }) => {
  const diagnostics = observeDiagnostics(page);
  const orderRequests: string[] = [];
  const orderResponses: Array<{ path: string; status: number }> = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/orders/")
      || path.startsWith("/api/v1/payment/payments/by-order/")
      || path.startsWith("/api/v1/fulfillment/orders/")
    ) {
      orderRequests.push(path);
    }
  });
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/orders/")
      || path.startsWith("/api/v1/payment/payments/by-order/")
      || path.startsWith("/api/v1/fulfillment/orders/")
    ) {
      orderResponses.push({ path, status: response.status() });
    }
  });

  await page.route(
    `**/api/v1/payment/payments/by-order/${pendingOrderNo}`,
    async (route) => {
      await route.fulfill({
        status: 404,
        contentType: "application/json",
        body: JSON.stringify({
          code: "RESOURCE_NOT_FOUND",
          message: "Payment not found",
          data: null,
          timestamp: "2026-08-02T00:00:00Z",
        }),
      });
    },
  );

  await loginCustomer(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await page.goto(`/orders/${pendingOrderNo}`);
  await expect(page.getByText("库存已经预占", { exact: true })).toHaveCount(1);
  await expect(page.getByRole("link", { name: "处理支付" })).toHaveAttribute(
    "href",
    "#payment-title",
  );
  await expect(page.getByRole("button", { name: "创建支付单" })).toBeVisible();
  await expectNoRootOverflow(page);

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/orders/${completedOrderNo}`);
  await expect(page.getByText("订单已完成", { exact: true })).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "履约与物流" })).toBeVisible();
  await expect(page.getByText("签收事实已经确认", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "评价本次购买" })).toHaveAttribute(
    "href",
    "#order-review-title",
  );

  const requestsBeforeException = orderRequests.length;
  await page.goto(`/orders/${paymentExceptionOrderNo}`);
  await expect(page.getByText("订单需要人工核对", { exact: true })).toHaveCount(1);
  await expect(page.locator(".order-overview.pj-status-notice--attention")).toBeVisible();
  await expect(page.getByRole("link", { name: "查看待核对事实" })).toHaveAttribute(
    "href",
    "#payment-title",
  );
  await expect(page.getByText("支付成功", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "履约与物流" })).toHaveCount(0);
  await expect(page.getByText("配送信息正在建立", { exact: true })).toHaveCount(0);
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  const exceptionRequests = orderRequests.slice(requestsBeforeException);
  expect(exceptionRequests).toContain(
    `/api/v1/trade/orders/${paymentExceptionOrderNo}`,
  );
  expect(exceptionRequests).toContain(
    `/api/v1/payment/payments/by-order/${paymentExceptionOrderNo}`,
  );
  expect(exceptionRequests.some((path) =>
    path.startsWith(`/api/v1/fulfillment/orders/${paymentExceptionOrderNo}`)))
    .toBe(false);
  expect(orderResponses).toEqual(expect.arrayContaining([
    {
      path: `/api/v1/trade/orders/${paymentExceptionOrderNo}`,
      status: 200,
    },
    {
      path: `/api/v1/payment/payments/by-order/${paymentExceptionOrderNo}`,
      status: 200,
    },
  ]));

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors.filter((message) =>
    !message.includes("404 (Not Found)"))).toEqual([]);
});
