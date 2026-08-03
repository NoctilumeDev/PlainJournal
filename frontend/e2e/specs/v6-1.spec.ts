import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const refreshTokenKey = "plain-journal:customer-refresh-token:v1";
const guestBagKey = "plain-journal:guest-bag:v1";
const pendingMergeKey = "plain-journal:guest-bag-merge:v1";

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
    !message.includes("401 (Unauthorized)")
    && !message.includes("503 (Service Unavailable)"));
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

async function resetCartFixture(request: APIRequestContext) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/cart/reset",
  );
  expect(response.ok()).toBe(true);
}

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

test.describe.configure({ mode: "serial" });

test("V6.1 login rejects an external return target and establishes account facts first", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  const identityRequests: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/api/v1/identity/")) {
      identityRequests.push(`${request.method()} ${path}`);
    }
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/login?returnTo=%2F%2Fevil.example");
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");
  await expect(page.locator(".pj-page-container.identity-page")).toBeVisible();
  await expect(page.locator(".pj-surface.identity-panel")).toBeVisible();

  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();

  await expect(page).toHaveURL("/account");
  await expect(page.getByRole("heading", { name: "Reader", level: 1 }))
    .toBeVisible();
  await expect(page.getByText("使用中", { exact: true })).toBeVisible();
  await expect(page.getByText("顾客", { exact: true })).toBeVisible();
  await expect(page.locator(".account-profile.pj-surface")).toBeVisible();
  await expect(page.getByText(/Trade|Marketing/u)).toHaveCount(0);
  expect(identityRequests).toEqual([
    "POST /api/v1/identity/auth/login",
    "GET /api/v1/identity/me",
  ]);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.1 registration keeps its request order before a safe product return in Subai", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  const identityRequests: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/api/v1/identity/")) {
      identityRequests.push(`${request.method()} ${path}`);
    }
  });
  await page.addInitScript(() => {
    localStorage.setItem("sujianji-theme", "subai");
  });

  await page.goto("/register?returnTo=%2Fproducts%2F2079000000000000001");
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await page.getByLabel("称呼").fill("Reader");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "创建账户 →" }).click();

  await expect(page).toHaveURL("/products/2079000000000000001");
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();
  expect(identityRequests).toEqual([
    "POST /api/v1/identity/auth/register",
    "POST /api/v1/identity/auth/login",
    "GET /api/v1/identity/me",
  ]);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.1 account keeps one merge key while an unknown result is recovered", async ({
  page,
  request,
}) => {
  await resetCartFixture(request);
  const diagnostics = observeDiagnostics(page);
  const mergeRequests: Array<{ key: string | null; body: unknown }> = [];
  let firstAttempt = true;
  await page.addInitScript(() => {
    localStorage.setItem("plain-journal:guest-bag:v1", JSON.stringify([{
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: "/images/catalog/canvas-commuter-tote.png",
    }]));
  });
  page.on("request", (requestEvent) => {
    const path = new URL(requestEvent.url()).pathname;
    if (path === "/api/v1/trade/cart/guest-merge") {
      mergeRequests.push({
        key: requestEvent.headers()["idempotency-key"] ?? null,
        body: requestEvent.postDataJSON(),
      });
    }
  });
  await page.route("**/api/v1/trade/cart/guest-merge", async (route) => {
    if (firstAttempt) {
      firstAttempt = false;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "SERVICE_UNAVAILABLE",
          message: "merge response unavailable",
          data: null,
          timestamp: "2026-08-02T00:00:00Z",
        }),
      });
      return;
    }
    await route.continue();
  });

  await loginCustomer(page);
  const unknown = page.locator(
    ".account-merge-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("合并结果待确认");
  await expect(unknown).toContainText("本地商品与重试键均已保留");
  await expect(page.locator(".account-merge-notice.pj-status-notice--success"))
    .toHaveCount(0);
  await expect.poll(() => page.evaluate((key) =>
    localStorage.getItem(key), pendingMergeKey)).not.toBeNull();

  await page.getByRole("button", { name: "使用原重试键再次确认" }).click();
  await expect(page.locator(".account-merge-notice.pj-status-notice--success"))
    .toContainText("购物袋已合并");
  await expect.poll(() => page.evaluate(
    ([bagStorageKey, pendingStorageKey]) => ({
      bag: localStorage.getItem(bagStorageKey),
      pending: localStorage.getItem(pendingStorageKey),
    }),
    [guestBagKey, pendingMergeKey],
  )).toEqual({ bag: "[]", pending: null });

  expect(mergeRequests).toHaveLength(2);
  expect(mergeRequests[0]?.key).toBeTruthy();
  expect(mergeRequests[1]).toEqual(mergeRequests[0]);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.1 logout 503 preserves the session until the local-only action is explicit", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  const logoutResponses: number[] = [];
  page.on("response", (response) => {
    if (new URL(response.url()).pathname === "/api/v1/identity/auth/logout") {
      logoutResponses.push(response.status());
    }
  });
  await page.route("**/api/v1/identity/auth/logout", async (route) => {
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SERVICE_UNAVAILABLE",
        message: "logout response unavailable",
        data: null,
        timestamp: "2026-08-02T00:00:00Z",
      }),
    });
  });

  await loginCustomer(page);
  await page.getByRole("button", { name: "退出", exact: true }).click();

  await expect(page).toHaveURL("/account");
  const unknown = page.locator(
    ".account-logout-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("服务端退出结果待确认");
  await expect(page.getByRole("heading", { name: "Reader", level: 1 }))
    .toBeVisible();
  await expect.poll(() => page.evaluate((key) =>
    localStorage.getItem(key), refreshTokenKey))
    .toBe("browser-customer-refresh-token");
  await expect(page.getByRole("button", { name: "仅清除此设备" }))
    .toBeVisible();

  await page.getByRole("button", { name: "仅清除此设备" }).click();
  await expect(page).toHaveURL("/");
  await expect(page.getByRole("link", { name: "登录", exact: true }))
    .toBeVisible();
  await expect.poll(() => page.evaluate((key) =>
    localStorage.getItem(key), refreshTokenKey)).toBeNull();
  expect(logoutResponses).toEqual([503]);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
