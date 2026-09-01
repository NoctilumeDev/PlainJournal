import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const pendingOrderNo = "ORD2079000000000008001";
const completedOrderNo = "ORD2079000000000007001";
const paymentExceptionOrderNo = "ORD2079000000000009001";

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

function unexpectedConsoleErrors(errors: string[]) {
  return errors.filter((message) =>
    !message.includes("404 (Not Found)")
    && !message.includes("503 (Service Unavailable)"));
}

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

async function resetPaymentFixture(request: APIRequestContext) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/payment/reset",
  );
  expect(response.ok()).toBe(true);
}

async function resetCheckoutFixture(request: APIRequestContext) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/checkout/reset",
  );
  expect(response.ok()).toBe(true);
}

async function expectNoRootOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBe(dimensions.clientWidth);
}

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

async function expectLoadedImage(page: Page, selector: string) {
  const image = page.locator(selector);
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((value) =>
    value instanceof HTMLImageElement
    && value.complete
    && value.naturalWidth > 0)).toBe(true);
}

function envelope(data: unknown, code = "OK", message = "success") {
  return {
    code,
    message,
    data,
    timestamp: "2026-08-02T00:00:00Z",
  };
}

test.describe.configure({ mode: "serial" });

test("V5.2 order list and payment keep PROCESSING visibly distinct from success", async ({
  page,
  request,
}) => {
  await resetPaymentFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requests: Array<{ method: string; path: string; status: number }> = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/orders")
      || path.startsWith("/api/v1/payment/payments")
    ) {
      requests.push({
        method: response.request().method(),
        path,
        status: response.status(),
      });
    }
  });

  await loginCustomer(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/orders");

  await expect(page.getByRole("heading", { name: "我的订单", level: 1 }))
    .toBeVisible();
  await expect(page.locator(".order-row")).toHaveCount(2);
  await expect(page.locator(".order-card")).toHaveCount(0);
  await expect(page.locator(".order-row").filter({ hasText: pendingOrderNo }))
    .toContainText("待支付");
  await expect(page.locator(".order-row").filter({ hasText: completedOrderNo }))
    .toContainText("已完成");
  await expectNoRootOverflow(page);

  const pendingRow = page.locator(".order-row").filter({ hasText: pendingOrderNo });
  await pendingRow.getByRole("link", { name: "查看并可取消" }).click();
  await expect(page.getByText("库存已经预占", { exact: true })).toBeVisible();
  await expect(page.locator(".order-line")).toContainText("单价 ¥199.00");
  await expect(page.locator(".order-line")).toContainText("¥398.00");
  await expect(page.locator(".order-summary dd").first()).toHaveText("¥398.00");
  await page.getByRole("button", { name: "创建支付单" }).click();

  const processingFeedback = page.locator(
    ".payment-feedback.pj-status-notice--processing",
  );
  await expect(processingFeedback).toContainText("支付单已建立");
  await expect(processingFeedback).toContainText("正在等待渠道返回最终结果");
  await expect(page.locator(".payment-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  await expect(page.getByRole("button", { name: "取消订单" })).toHaveCount(0);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(requests).toEqual(expect.arrayContaining([
    { method: "GET", path: "/api/v1/trade/orders/page", status: 200 },
    {
      method: "GET",
      path: `/api/v1/trade/orders/${pendingOrderNo}`,
      status: 200,
    },
    { method: "POST", path: "/api/v1/payment/payments", status: 200 },
  ]));
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V5.2 exception and receipt-unknown paths preserve service authority in both themes", async ({
  page,
  request,
}) => {
  await resetPaymentFixture(request);
  const diagnostics = observeDiagnostics(page);
  const orderRequests: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/orders/")
      || path.startsWith("/api/v1/payment/payments/")
      || path.startsWith("/api/v1/fulfillment/orders/")
    ) {
      orderRequests.push(`${request.method()} ${path}`);
    }
  });

  await loginCustomer(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/orders/${paymentExceptionOrderNo}`);
  await expect(page.locator(".order-overview.pj-status-notice--attention"))
    .toContainText("订单需要人工核对");
  await expect(page.getByText("支付成功", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "履约与物流" })).toHaveCount(0);
  expect(orderRequests.some((value) =>
    value.includes(`/api/v1/fulfillment/orders/${paymentExceptionOrderNo}`)))
    .toBe(false);

  await page.goto("/index");
  await page.getByLabel("素白").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");

  await page.route(
    `**/api/v1/trade/orders/${completedOrderNo}`,
    async (route) => {
      const response = await route.fetch();
      const payload = await response.json();
      payload.data.status = "SHIPPED";
      payload.data.updatedAt = "2026-08-02T00:01:00Z";
      await route.fulfill({ response, json: payload });
    },
  );
  await page.route(
    `**/api/v1/fulfillment/orders/${completedOrderNo}`,
    async (route) => {
      const response = await route.fetch();
      const payload = await response.json();
      payload.data.status = "SHIPPED";
      payload.data.signedAt = null;
      payload.data.updatedAt = "2026-08-02T00:01:00Z";
      await route.fulfill({ response, json: payload });
    },
  );
  await page.route(
    `**/api/v1/fulfillment/orders/${completedOrderNo}/confirm-receipt`,
    async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "REMOTE_DEPENDENCY_UNAVAILABLE",
          "confirmation response unavailable",
        )),
      });
    },
  );

  await page.goto(`/orders/${completedOrderNo}`);
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await expect(page.getByRole("heading", { name: "履约与物流" })).toBeVisible();
  await expectLoadedImage(page, ".fulfillment-visual img");
  await expect(page.getByText("包裹已经发出", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "确认收货" })).toBeVisible();

  await page.getByRole("button", { name: "确认收货" }).click();
  await page.getByRole("button", { name: "确认已经收货" }).click();
  const unknownNotice = page.locator(
    ".fulfillment-section .pj-status-notice--unknown",
  );
  await expect(unknownNotice).toContainText("确认收货结果待确认");
  await expect(unknownNotice).toContainText("仍未确认时可安全重试同一路径");
  await expect(page.locator(".fulfillment-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  await expect(page.getByText("订单已完成", { exact: true })).toHaveCount(0);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  await page.goto("/index");
  await page.getByLabel("青荷").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");

  expect(orderRequests).toContain(
    `POST /api/v1/fulfillment/orders/${completedOrderNo}/confirm-receipt`,
  );
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V5.2 fresh checkout reaches its own PROCESSING payment without blue control drift", async ({
  page,
  request,
}) => {
  await resetCheckoutFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requests: Array<{ method: string; path: string; status: number }> = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/orders")
      || path.startsWith("/api/v1/payment/payments")
    ) {
      requests.push({
        method: response.request().method(),
        path,
        status: response.status(),
      });
    }
  });

  await loginCustomer(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/bag");
  const bagSelection = page.locator(
    ".account-cart-row__selection input[type='checkbox']",
  );
  await expect(bagSelection).toBeChecked();
  const bagAccent = await bagSelection.evaluate((element) =>
    getComputedStyle(element).accentColor);
  const expectedAccent = await page.evaluate(() => {
    const probe = document.createElement("input");
    probe.style.accentColor = "var(--pj-action-primary)";
    document.body.append(probe);
    const value = getComputedStyle(probe).accentColor;
    probe.remove();
    return value;
  });
  expect(bagAccent).toBe(expectedAccent);

  await page.goto("/checkout");
  const addressChoice = page.locator(
    ".transaction-choice input[type='radio']",
  ).first();
  await expect(addressChoice).toBeChecked();
  await expect.poll(() => addressChoice.evaluate((element) =>
    getComputedStyle(element).accentColor)).toBe(bagAccent);

  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  await expect(page.getByText("权威核对已完成")).toBeVisible();
  await page.getByRole("button", { name: "以当前事实提交订单 →" }).click();

  await expect(page).toHaveURL(/\/orders\/ORD2079000000000010001$/u);
  await expect(page.locator(".order-line")).toContainText("单价 ¥189.00");
  await expect(page.locator(".order-line")).toContainText("¥378.00");
  await expect(page.locator(".order-summary__total")).toContainText("¥378.00");
  await page.getByRole("button", { name: "创建支付单" }).click();

  const processingNotices = page.locator(
    ".payment-section .pj-status-notice--processing",
  );
  await expect(processingNotices.first()).toContainText("支付结果正在确认");
  await expect(processingNotices.last()).toContainText("支付单已建立");
  const paymentColors = await processingNotices.first().evaluate((element) => {
    const probe = document.createElement("span");
    probe.style.background = "var(--pj-surface-soft)";
    probe.style.color = "var(--pj-brand-primary-hover)";
    document.body.append(probe);
    const expected = getComputedStyle(probe);
    const actual = getComputedStyle(element);
    const result = {
      background: actual.backgroundColor,
      border: actual.borderLeftColor,
      text: actual.color,
      expectedBackground: expected.backgroundColor,
      expectedAccent: expected.color,
    };
    probe.remove();
    return result;
  });
  expect(paymentColors).toMatchObject({
    background: paymentColors.expectedBackground,
    border: paymentColors.expectedAccent,
    text: paymentColors.expectedAccent,
  });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  expect(requests).toEqual(expect.arrayContaining([
    { method: "POST", path: "/api/v1/trade/orders", status: 200 },
    {
      method: "GET",
      path: "/api/v1/trade/orders/ORD2079000000000010001",
      status: 200,
    },
    { method: "POST", path: "/api/v1/payment/payments", status: 200 },
  ]));
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
