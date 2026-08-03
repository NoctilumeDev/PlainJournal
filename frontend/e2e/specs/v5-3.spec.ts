import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const afterSaleNo = "AS2079000000000003001";
const orderNo = "ORD2079000000000003002";
const returnReceiptNo = "RET2079000000000003003";

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

function envelope(data: unknown, code = "OK", message = "success") {
  return {
    code,
    message,
    data,
    timestamp: "2026-08-02T00:00:00Z",
  };
}

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

async function resetAfterSaleFixture(request: APIRequestContext) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/after-sale/reset",
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

test.describe.configure({ mode: "serial" });

test("V5.3 reverse journey confirms shipment only from Fulfillment authority", async ({
  page,
  request,
}) => {
  await resetAfterSaleFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requests: Array<{ method: string; path: string; status: number }> = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/after-sales")
      || path.startsWith("/api/v1/fulfillment/returns")
      || path.startsWith("/api/v1/payment/refunds")
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
  await page.goto("/after-sales");

  await expect(page.getByRole("heading", { name: "售后服务", level: 1 }))
    .toBeVisible();
  await expect(page.locator(".after-sale-row")).toHaveCount(1);
  await expect(page.locator(".order-card")).toHaveCount(0);
  await page.getByRole("link", { name: new RegExp(orderNo, "u") }).click();

  await expect(page.getByRole("heading", { name: "售后详情", level: 1 }))
    .toBeVisible();
  await expect(page.getByText("顾客", { exact: true })).toBeVisible();
  await expect(page.locator(".return-receipt-state.pj-status-notice--warning"))
    .toContainText("等待寄回");
  await expect(page.locator(".refund-state.pj-status-notice--processing"))
    .toContainText("退款处理中");
  await expect(page.locator(".refund-state.pj-status-notice--refunded"))
    .toHaveCount(0);

  await page.getByLabel("承运商代码").fill("SF");
  await page.getByLabel("运单号").fill("SF1234567890");
  await page.getByRole("button", { name: "提交寄回信息" }).click();

  const successNotice = page.locator(
    ".after-sale-feedback.pj-status-notice--success",
  );
  await expect(successNotice).toContainText("寄回事实已确认");
  await expect(page.locator(".after-sale-feedback.pj-status-notice--unknown"))
    .toHaveCount(0);
  await expect(page.getByText("SF / SF1234567890")).toBeVisible();
  await expect(page.getByText("承运商与 Fulfillment", { exact: true })).toBeVisible();

  const boundary = page.locator(".after-sale-boundary");
  await expect(boundary.locator("dl div").filter({ hasText: "售后" }))
    .toContainText("RETURNING");
  await expect(boundary.locator("dl div").filter({ hasText: "退货" }))
    .toContainText("RETURNING");
  await expect(boundary.locator("dl div").filter({ hasText: "退款" }))
    .toContainText("PROCESSING");

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(requests).toEqual(expect.arrayContaining([
    {
      method: "GET",
      path: `/api/v1/trade/after-sales/${afterSaleNo}`,
      status: 200,
    },
    {
      method: "POST",
      path: `/api/v1/fulfillment/returns/${returnReceiptNo}/shipment`,
      status: 200,
    },
    {
      method: "GET",
      path: `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`,
      status: 200,
    },
  ]));
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V5.3 unknown shipment and NEEDS_ATTENTION stay non-success in both themes", async ({
  page,
  request,
}) => {
  await resetAfterSaleFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requestPaths: string[] = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      path.startsWith("/api/v1/trade/after-sales")
      || path.startsWith("/api/v1/fulfillment/returns")
      || path.startsWith("/api/v1/payment/refunds")
    ) {
      requestPaths.push(`${browserRequest.method()} ${path}`);
    }
  });

  await page.route(
    `**/api/v1/fulfillment/returns/${returnReceiptNo}/shipment`,
    async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "REMOTE_DEPENDENCY_UNAVAILABLE",
          "shipment response unavailable",
        )),
      });
    },
  );

  await loginCustomer(page);
  await page.goto(`/after-sales/${afterSaleNo}`);
  await page.getByLabel("承运商代码").fill("SF");
  await page.getByLabel("运单号").fill("SF1234567890");
  await page.getByRole("button", { name: "提交寄回信息" }).click();

  const unknownNotice = page.locator(
    ".after-sale-feedback.pj-status-notice--unknown",
  );
  await expect(unknownNotice).toContainText("寄回结果待确认");
  await expect(unknownNotice).toContainText("不要更换运单号重复提交");
  await expect(page.locator(".after-sale-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  await expect(
    page.locator(".after-sale-boundary dd").filter({ hasText: "WAIT_SHIPMENT" }),
  ).toBeVisible();

  await page.unroute(`**/api/v1/fulfillment/returns/${returnReceiptNo}/shipment`);
  await page.route(
    `**/api/v1/trade/after-sales/${afterSaleNo}`,
    async (route) => {
      const response = await route.fetch();
      const payload = await response.json();
      payload.data.status = "REFUND_FAILED";
      payload.data.updatedAt = "2026-08-02T00:10:00Z";
      await route.fulfill({ response, json: payload });
    },
  );
  await page.route(
    `**/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`,
    async (route) => {
      const response = await route.fetch();
      const payload = await response.json();
      payload.data.status = "PROCESSING";
      payload.data.requestStatus = "NEEDS_ATTENTION";
      payload.data.requestAttempts = 3;
      payload.data.updatedAt = "2026-08-02T00:10:00Z";
      await route.fulfill({ response, json: payload });
    },
  );

  await page.getByRole("button", { name: "刷新售后进度" }).click();
  const attentionNotice = page.locator(
    ".refund-state.pj-status-notice--attention",
  );
  await expect(attentionNotice).toContainText("需要处理");
  await expect(attentionNotice).toContainText("授权、幂等与审计边界");
  await expect(page.locator(".refund-state.pj-status-notice--refunded"))
    .toHaveCount(0);
  await expect(page.getByRole("button", { name: /补偿/u })).toHaveCount(0);

  await page.goto("/index");
  await page.getByLabel("素白").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await page.goto(`/after-sales/${afterSaleNo}`);
  await expect(page.locator(".refund-state.pj-status-notice--attention"))
    .toContainText("需要处理");

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  await page.goto("/index");
  await page.getByLabel("青荷").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");

  expect(requestPaths).toContain(
    `POST /api/v1/fulfillment/returns/${returnReceiptNo}/shipment`,
  );
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V5.3 lost cancellation response remains unknown until Trade proves CANCELED", async ({
  page,
  request,
}) => {
  await resetAfterSaleFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requests: string[] = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (path.startsWith("/api/v1/trade/after-sales")) {
      requests.push(`${browserRequest.method()} ${path}`);
    }
  });

  await page.route(
    `**/api/v1/trade/after-sales/${afterSaleNo}`,
    async (route) => {
      const response = await route.fetch();
      const payload = await response.json();
      payload.data.status = "APPLIED";
      payload.data.reviewReason = null;
      payload.data.returnReceiptNo = null;
      payload.data.refundNo = null;
      payload.data.approvedAt = null;
      await route.fulfill({ response, json: payload });
    },
  );
  await page.route("**/api/v1/fulfillment/returns", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(envelope([])),
    });
  });
  await page.route(
    `**/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`,
    async (route) => {
      await route.fulfill({
        status: 404,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "RESOURCE_NOT_FOUND",
          "refund not found",
        )),
      });
    },
  );
  await page.route(
    `**/api/v1/trade/after-sales/${afterSaleNo}/cancel`,
    async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "REMOTE_DEPENDENCY_UNAVAILABLE",
          "cancellation response unavailable",
        )),
      });
    },
  );

  await loginCustomer(page);
  await page.goto(`/after-sales/${afterSaleNo}`);
  await expect(page.getByRole("button", { name: "取消售后申请" })).toBeVisible();
  await page.getByRole("button", { name: "取消售后申请" }).click();
  await page.getByRole("button", { name: "确认取消申请" }).click();

  const unknownNotice = page.locator(
    ".after-sale-feedback.pj-status-notice--unknown",
  );
  await expect(unknownNotice).toContainText("取消结果待确认");
  await expect(page.locator(".after-sale-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  await expect(page.getByText("APPLIED", { exact: true }).last()).toBeVisible();

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  expect(requests).toContain(
    `POST /api/v1/trade/after-sales/${afterSaleNo}/cancel`,
  );
  expect(requests.filter((value) =>
    value === `GET /api/v1/trade/after-sales/${afterSaleNo}`)).toHaveLength(2);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
