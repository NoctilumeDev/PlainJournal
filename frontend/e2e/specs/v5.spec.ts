import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const ownerId = "2079000000000000999";
const productId = "2079000000000000001";
const skuId = "2079000000000000011";

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
    !message.includes("net::ERR_FAILED")
    && !message.includes("404 (Not Found)"));
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
  await expect(page).toHaveURL(/\/account$/);
}

function envelope(data: unknown, code = "OK", message = "success") {
  return JSON.stringify({
    code,
    message,
    data,
    timestamp: "2026-08-02T00:00:00Z",
  });
}

test.describe.configure({ mode: "serial" });

test("V5 bag keeps device and account facts separate while a merge result is unknown", async ({
  page,
  request,
}) => {
  await resetCartFixture(request);
  const diagnostics = observeDiagnostics(page);
  const mergeRequests: Array<{ key: string | null; body: unknown }> = [];
  let loseFirstMergeResponse = true;

  await page.addInitScript(() => {
    if (sessionStorage.getItem("plain-journal:v5-guest-seeded")) {
      return;
    }
    sessionStorage.setItem("plain-journal:v5-guest-seeded", "true");
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
    const url = new URL(requestEvent.url());
    if (url.pathname === "/api/v1/trade/cart/guest-merge") {
      mergeRequests.push({
        key: requestEvent.headers()["idempotency-key"] ?? null,
        body: requestEvent.postDataJSON(),
      });
    }
  });
  await page.route("**/api/v1/trade/cart/guest-merge", async (route) => {
    if (loseFirstMergeResponse) {
      loseFirstMergeResponse = false;
      await route.fetch();
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/bag");
  await expect(page.getByRole("heading", { name: "购物袋", level: 1 })).toBeVisible();
  await expect(page.getByText("当前设备商品", { exact: true })).toBeVisible();
  const guestImage = page.locator(".guest-bag-row__media img");
  await expect(guestImage).toHaveAttribute("alt", "帆布通勤袋");
  await expect.poll(() => guestImage.evaluate((image) =>
    image instanceof HTMLImageElement
    && image.complete
    && image.naturalWidth > 0)).toBe(true);
  await expect(page.locator(".bag-summary__amount")).toContainText("¥189.00");
  await expect(page.getByText("购物袋不代表库存已锁定", { exact: false }))
    .toBeVisible();
  await expectNoRootOverflow(page);

  await page.getByRole("link", { name: "登录并安全合并 →" }).click();
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL("/bag");
  await expect(page.locator(".bag-device-pending.pj-status-notice--unknown"))
    .toContainText("尚未确认移除的游客商品");
  await expect(page.getByText("合并结果暂时未知", { exact: false })).toBeVisible();
  await expect(page.locator(".bag-summary__amount")).toContainText("¥567.00");
  await expect(page.locator(".bag-device-pending")).toContainText("1 件");
  await expect(page.locator(".bag-device-pending"))
    .toContainText("这些商品不计入上方账户小计");
  await expect(page.locator(".bag-summary__amount")).not.toContainText("¥756.00");
  await expect(page.locator(".bag-device-pending.pj-status-notice--danger"))
    .toHaveCount(0);

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  await page.getByRole("button", { name: "使用原重试键再次确认" }).click();
  await expect(page.locator(".bag-device-pending")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => ({
    bag: localStorage.getItem("plain-journal:guest-bag:v1"),
    pending: localStorage.getItem("plain-journal:guest-bag-merge:v1"),
  }))).toEqual({ bag: "[]", pending: null });

  expect(mergeRequests).toHaveLength(2);
  expect(mergeRequests[0]?.key).toBeTruthy();
  expect(mergeRequests[1]).toEqual(mergeRequests[0]);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V5 checkout presents preview and three-domain authority as one continuous journey", async ({
  page,
  request,
}) => {
  await resetCartFixture(request);
  const diagnostics = observeDiagnostics(page);
  const requests: Array<{ method: string; path: string; status: number }> = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (
      url.pathname.startsWith("/api/v1/identity/addresses")
      || url.pathname.startsWith("/api/v1/trade/cart/items")
      || url.pathname.startsWith("/api/v1/marketing/")
      || url.pathname.startsWith("/api/v1/catalog/products/")
      || url.pathname.startsWith("/api/v1/inventory/stocks/")
    ) {
      requests.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });

  await loginCustomer(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/checkout");
  await expect(page.getByRole("heading", { name: "订单确认", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "选择订单收货地址" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "选择要用于订单的权益" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "账户购物车快照" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "金额明细" })).toBeVisible();

  await page.getByLabel(/COUPON-10/u).check();
  await page.getByRole("button", { name: "重新试算金额 →" }).click();
  await expect(page.getByText("¥368.00", { exact: true })).toBeVisible();
  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  const authorityNotice = page.locator(
    ".checkout-sidebar__notice.pj-status-notice--success",
  );
  await expect(authorityNotice).toContainText("权威核对已完成");
  await expect(authorityNotice)
    .toContainText("Catalog、Inventory 与 Marketing");
  await expect(page.getByText(/可用 18 件/u)).toBeVisible();
  await expect(page.getByRole("button", { name: "以当前事实提交订单 →" }))
    .toBeEnabled();
  await expectNoRootOverflow(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);

  expect(requests).toEqual(expect.arrayContaining([
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
  expect(requests.every((response) => response.status === 200)).toBe(true);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test("V5 checkout blocks insufficient stock and keeps a lost order response unknown", async ({
  page,
  request,
}) => {
  await resetCartFixture(request);
  const diagnostics = observeDiagnostics(page);
  const createKeys: string[] = [];
  let available = 1;

  await page.route(`**/api/v1/inventory/stocks/${skuId}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: envelope({
        skuId,
        onHand: available + 2,
        reserved: 2,
        available,
      }),
    });
  });
  await page.route("**/api/v1/trade/orders**", async (route) => {
    const requestEvent = route.request();
    const url = new URL(requestEvent.url());
    if (
      requestEvent.method() === "GET"
      && url.pathname.startsWith("/api/v1/trade/orders/by-idempotency-key/")
    ) {
      await route.fulfill({
        status: 404,
        contentType: "application/json",
        body: envelope(null, "RESOURCE_NOT_FOUND", "order not found"),
      });
      return;
    }
    if (
      requestEvent.method() === "POST"
      && url.pathname === "/api/v1/trade/orders"
    ) {
      createKeys.push(requestEvent.headers()["idempotency-key"] ?? "");
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await loginCustomer(page);
  await page.goto("/checkout");
  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  let authorityNotice = page.locator(
    ".checkout-sidebar__notice.pj-status-notice--warning",
  );
  await expect(authorityNotice).toContainText("权威核对已完成");
  await expect(authorityNotice).toContainText("可用库存不足");
  await expect(page.getByRole("button", { name: "以当前事实提交订单 →" }))
    .toBeDisabled();
  expect(createKeys).toEqual([]);

  available = 18;
  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  authorityNotice = page.locator(
    ".checkout-sidebar__notice.pj-status-notice--success",
  );
  await expect(authorityNotice).toContainText("权威核对已完成");
  await page.setViewportSize({ width: 320, height: 800 });
  await page.getByRole("button", { name: "以当前事实提交订单 →" }).click();

  const unknownNotice = page.locator(
    ".checkout-pending.pj-status-notice--unknown",
  );
  await expect(unknownNotice).toContainText("订单结果尚未确认");
  await expect(unknownNotice).toContainText("请求键与固定载荷已保留");
  await expect(unknownNotice).toContainText("查询订单结果");
  await expect(unknownNotice).toContainText("使用原请求安全重试");
  await expect(page.locator(".checkout-pending.pj-status-notice--danger"))
    .toHaveCount(0);
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  await page.getByRole("button", { name: "使用原请求安全重试" }).click();
  await expect.poll(() => createKeys.length).toBe(2);
  await expect(unknownNotice).toBeVisible();
  await expectNoRootOverflow(page);

  const stored = await page.evaluate((key) =>
    JSON.parse(localStorage.getItem(key) ?? "null"),
  `plain-journal:pending-order:v2:${ownerId}`) as {
    key: string;
    request: unknown;
  } | null;
  expect(stored?.key).toBe(createKeys[0]);
  expect(createKeys[1]).toBe(createKeys[0]);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
