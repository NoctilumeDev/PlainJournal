import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const customerId = "2079000000000000999";
const alternateCustomerId = "2079000000000002999";

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
    !message.includes("503 (Service Unavailable)"));
}

function envelope(data: unknown, code = "OK", message = "success") {
  return {
    code,
    message,
    data,
    timestamp: "2026-08-02T00:00:00Z",
  };
}

async function resetAddressFixture(request: APIRequestContext) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/addresses/reset",
  );
  expect(response.ok()).toBe(true);
}

async function loginCustomer(
  page: Page,
  email = "reader@example.com",
) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill(email);
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

async function fillAddress(page: Page, recipientName: string, detailAddress: string) {
  await page.getByLabel("收货人").fill(recipientName);
  await page.getByLabel("联系电话").fill("+86 13700000000");
  await page.getByLabel("省份").fill("浙江省");
  await page.getByLabel("省级代码").fill("330000");
  await page.getByLabel("城市").fill("杭州市");
  await page.getByLabel("市级代码").fill("330100");
  await page.locator("#district").fill("上城区");
  await page.getByLabel("区县代码").fill("330102");
  await page.getByLabel("详细地址").fill(detailAddress);
  await page.getByLabel("邮政编码（可选）").fill("310000");
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

function benefit(
  benefitNo: string,
  userId: string,
  status: "AVAILABLE" | "LOCKED" | "REDEEMED",
  overrides: Record<string, unknown> = {},
) {
  return {
    benefitNo,
    userId,
    ruleCode: "COUPON-10",
    benefitType: "COUPON",
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    status,
    lockedOrderNo: null,
    redeemedOrderNo: null,
    validFrom: "2026-08-01T00:00:00Z",
    validUntil: "2026-09-01T00:00:00Z",
    regions: [],
    ...overrides,
  };
}

test.describe.configure({ mode: "serial" });

test("V6.2 address management keeps one factual CRUD journey", async ({
  page,
  request,
}) => {
  await resetAddressFixture(request);
  const diagnostics = observeDiagnostics(page);
  const addressRequests: Array<{ method: string; path: string }> = [];
  let updateBody: Record<string, unknown> | null = null;
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (path.startsWith("/api/v1/identity/addresses")) {
      addressRequests.push({ method: browserRequest.method(), path });
      if (
        browserRequest.method() === "PUT"
        && /^\/api\/v1\/identity\/addresses\/\d+$/u.test(path)
      ) {
        updateBody = browserRequest.postDataJSON() as Record<string, unknown>;
      }
    }
  });

  await loginCustomer(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/account/addresses");
  await expect(page.locator(".pj-page-container.address-page")).toBeVisible();
  await expect(page.locator(".pj-surface.address-form")).toBeVisible();
  const original = page.getByRole("article").filter({ hasText: "Test Customer" });
  await expect(original).toContainText("默认地址");

  await fillAddress(page, "V6 Address", "湖滨路 6 号");
  await page.getByRole("button", { name: "保存新收货地址" }).click();
  await expect(page.getByText("新收货地址已确认。")).toBeVisible();

  let created = page.getByRole("article").filter({ hasText: "V6 Address" });
  await created.getByRole("button", { name: "修改" }).click();
  await page.getByLabel("详细地址").fill("湖滨路 7 号");
  await page.getByRole("button", { name: "保存地址修改" }).click();
  await expect(page.getByText("地址修改已确认。")).toBeVisible();
  created = page.getByRole("article").filter({ hasText: "V6 Address" });
  await expect(created).toContainText("湖滨路 7 号");
  expect(updateBody).not.toHaveProperty("version");

  await created.getByRole("button", { name: "设为默认地址" }).click();
  await expect(created).toContainText("默认地址");
  await created.getByRole("button", { name: "删除" }).click();
  let confirmation = page.getByRole("group", {
    name: "删除 V6 Address 的地址",
  });
  await confirmation.getByRole("button", { name: "保留地址" }).click();
  await expect(created).toBeVisible();
  await created.getByRole("button", { name: "删除" }).click();
  confirmation = page.getByRole("group", {
    name: "删除 V6 Address 的地址",
  });
  await confirmation.getByRole("button", { name: "删除这个地址" }).click();
  await expect(created).toHaveCount(0);
  await expect(original).toContainText("默认地址");

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(addressRequests.some((entry) => entry.method === "POST")).toBe(true);
  expect(addressRequests.some((entry) => entry.method === "PUT")).toBe(true);
  expect(addressRequests.some((entry) => entry.method === "DELETE")).toBe(true);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.2 a committed address with a lost response stays unknown until reread", async ({
  page,
  request,
}) => {
  await resetAddressFixture(request);
  const diagnostics = observeDiagnostics(page);
  let createRequests = 0;
  await page.route("**/api/v1/identity/addresses", async (route) => {
    if (route.request().method() !== "POST") {
      await route.continue();
      return;
    }
    createRequests += 1;
    const committed = await route.fetch();
    expect(committed.ok()).toBe(true);
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify(envelope(
        null,
        "SERVICE_UNAVAILABLE",
        "address response unavailable",
      )),
    });
  });

  await loginCustomer(page);
  await page.goto("/account/addresses");
  await fillAddress(page, "Recovery Address", "湖滨路 8 号");
  await page.getByRole("button", { name: "保存新收货地址" }).click();

  const unknown = page.locator(".address-error.pj-status-notice--unknown");
  await expect(unknown).toContainText("地址操作结果待确认");
  await expect(unknown).toContainText("先重新读取地址");
  await expect(page.locator(".address-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  await expect(page.getByLabel("收货人")).toHaveValue("Recovery Address");

  await page.getByRole("button", { name: "重新读取地址" }).click();
  await expect(page.getByRole("article").filter({ hasText: "Recovery Address" }))
    .toContainText("湖滨路 8 号");
  await expect(page.getByText("最新地址列表已重新读取，请核对当前事实。"))
    .toBeVisible();
  await expect(unknown).toHaveCount(0);
  expect(createRequests).toBe(1);

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.2 benefit lifecycle and owner switch remain visually and factually isolated", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  const benefitAuthorizations: string[] = [];
  await page.route("**/api/v1/marketing/benefits", async (route) => {
    const authorization = route.request().headers()["authorization"] ?? "";
    benefitAuthorizations.push(authorization);
    const alternate = authorization.includes("customer-two");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(envelope(alternate
        ? [benefit("BEN-SECOND", alternateCustomerId, "AVAILABLE")]
        : [
            benefit("BEN-AVAILABLE", customerId, "AVAILABLE"),
            benefit("BEN-LOCKED", customerId, "LOCKED", {
              lockedOrderNo: "ORD-LOCKED",
            }),
            benefit("BEN-USED", customerId, "REDEEMED", {
              redeemedOrderNo: "ORD-USED",
            }),
          ])),
    });
  });

  await loginCustomer(page);
  await page.goto("/account/benefits");
  await expect(page.locator(".benefit-row")).toHaveCount(3);
  await expect(page.locator(".benefit-row__status.pj-status-notice--success"))
    .toContainText("可用");
  await expect(page.locator(".benefit-row__status.pj-status-notice--processing"))
    .toContainText("订单已锁定");
  await expect(page.locator(".benefit-row__status.pj-status-notice--neutral"))
    .toContainText("已使用");
  await expect(page.getByText(/Marketing 权益事实/u)).toHaveCount(0);
  await expect(page.locator(".benefit-card")).toHaveCount(0);
  await expect(page.locator(".order-status-badge")).toHaveCount(0);

  await page.goto("/index");
  await page.getByLabel("素白").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL(/\/$/u);
  await loginCustomer(page, "reader-two@example.com");
  await page.goto("/account/benefits");

  await expect(page.locator(".benefit-row")).toHaveCount(1);
  await expect(page.getByText("BEN-SECOND")).toBeVisible();
  await expect(page.getByText("BEN-AVAILABLE")).toHaveCount(0);
  await expect(page.getByText("BEN-LOCKED")).toHaveCount(0);
  await expect(page.getByText("BEN-USED")).toHaveCount(0);
  expect(benefitAuthorizations)
    .toContain("Bearer browser-customer-access-token-rotated");
  expect(benefitAuthorizations)
    .toContain("Bearer browser-customer-two-access-token-rotated");

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
