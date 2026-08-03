import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/);
}

test.describe.configure({ mode: "serial" });

test("public catalog browsing preserves URL facts and string identities", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("/");
  await expect(
    page.getByRole("heading", {
      name: "真正值得使用的东西，不需要促销噪音。",
    }),
  ).toBeVisible();
  await expect(page.locator(".product-card__link").filter({ hasText: "帆布通勤袋" }))
    .toHaveAttribute("href", "/products/2079000000000000001");

  await page.goto("/products?category=carry");
  await expect(page.getByRole("heading", { name: "随身用品" })).toBeVisible();
  await expect(page.locator(".catalog-intro__count")).toContainText("1件商品");

  await page.goto("/search?q=%E9%80%9A%E5%8B%A4");
  await expect(page.getByRole("heading", { name: "“通勤”" })).toBeVisible();
  await expect(page.locator(".search-results__heading > p")).toContainText("1件匹配");
  await page.reload();
  await expect(page.getByRole("heading", { name: "“通勤”" })).toBeVisible();

  await page.getByRole("link", { name: /帆布通勤袋/ }).click();
  await expect(page).toHaveURL("/products/2079000000000000001");
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  expect(pageErrors).toEqual([]);
});

test("customer session clears expired credentials and preserves the guarded return target", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const refreshStatuses: number[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("response", (response) => {
    if (new URL(response.url()).pathname === "/api/v1/identity/auth/refresh") {
      refreshStatuses.push(response.status());
    }
  });
  await page.addInitScript(() => {
    if (sessionStorage.getItem("plain-journal:e2e-expired-token-seeded")) {
      return;
    }
    sessionStorage.setItem("plain-journal:e2e-expired-token-seeded", "true");
    localStorage.setItem(
      "plain-journal:customer-refresh-token:v1",
      "expired-customer-refresh-token",
    );
  });

  await page.goto("/account");
  await expect(page).toHaveURL((url) =>
    url.pathname === "/login" && url.searchParams.get("returnTo") === "/account");
  await expect(page.getByRole("heading", { name: "继续你的素简记。" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem("plain-journal:customer-refresh-token:v1")))
    .toBeNull();

  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL("/account");
  await expect(page.getByRole("heading", { name: "Reader" })).toBeVisible();
  await expect(page.getByText("2079000000000000999")).toBeVisible();

  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL("/");
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem("plain-journal:customer-refresh-token:v1")))
    .toBeNull();
  await page.goto("/account");
  await expect(page).toHaveURL((url) =>
    url.pathname === "/login" && url.searchParams.get("returnTo") === "/account");
  await expectNoSeriousAccessibilityViolations(page);

  expect(pageErrors).toEqual([]);
  expect(refreshStatuses).toEqual([401]);
  expect(consoleErrors.filter((message) =>
    !message.includes("401 (Unauthorized)"))).toEqual([]);
});

test("registration establishes a session before honoring a safe return target", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("/register?returnTo=%2Fproducts%2F2079000000000000001");
  await page.getByLabel("称呼").fill("Reader");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "创建账户 →" }).click();

  await expect(page).toHaveURL("/products/2079000000000000001");
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 1 }))
    .toBeVisible();
  await expect(page.getByRole("link", { name: "账户" })).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  expect(pageErrors).toEqual([]);
});

test("address management keeps server facts isolated across customer owners", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const addressResponses: Array<{ method: string; path: string; status: number }> = [];
  let updateBody: Record<string, unknown> | null = null;
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (
      request.method() === "PUT"
      && /^\/api\/v1\/identity\/addresses\/\d+$/.test(url.pathname)
    ) {
      updateBody = request.postDataJSON() as Record<string, unknown>;
    }
  });
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/v1/identity/addresses")) {
      addressResponses.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });

  await loginCustomer(page);
  await page.goto("/account/addresses");
  await expect(page.getByRole("heading", { name: "收货信息", level: 1 })).toBeVisible();
  const originalAddress = page.getByRole("article").filter({ hasText: "Test Customer" });
  await expect(originalAddress).toContainText("默认地址");

  await page.getByLabel("收货人").fill("Browser Address");
  await page.getByLabel("联系电话").fill("+86 13700000000");
  await page.getByLabel("省份").fill("浙江省");
  await page.getByLabel("省级代码").fill("330000");
  await page.getByLabel("城市").fill("杭州市");
  await page.getByLabel("市级代码").fill("330100");
  await page.locator("#district").fill("上城区");
  await page.getByLabel("区县代码").fill("330102");
  await page.getByLabel("详细地址").fill("湖滨路 2 号");
  await page.getByLabel("邮政编码（可选）").fill("310000");
  await page.getByRole("button", { name: "保存新收货地址" }).click();
  await expect(page.getByText("新收货地址已确认。")).toBeVisible();

  let createdAddress = page.getByRole("article").filter({ hasText: "Browser Address" });
  await expect(createdAddress).toContainText("湖滨路 2 号");
  await createdAddress.getByRole("button", { name: "设为默认地址" }).click();
  await expect(createdAddress).toContainText("默认地址");
  await expect(originalAddress.getByText("默认地址", { exact: true })).toHaveCount(0);

  await createdAddress.getByRole("button", { name: "修改" }).click();
  await page.getByLabel("详细地址").fill("湖滨路 3 号");
  await page.getByRole("button", { name: "保存地址修改" }).click();
  await expect(page.getByText("地址修改已确认。")).toBeVisible();
  createdAddress = page.getByRole("article").filter({ hasText: "Browser Address" });
  await expect(createdAddress).toContainText("湖滨路 3 号");
  expect(updateBody).not.toHaveProperty("version");

  await createdAddress.getByRole("button", { name: "删除" }).click();
  let confirmation = page.getByRole("group", { name: "删除 Browser Address 的地址" });
  await confirmation.getByRole("button", { name: "保留地址" }).click();
  await expect(createdAddress).toBeVisible();
  await createdAddress.getByRole("button", { name: "删除" }).click();
  confirmation = page.getByRole("group", { name: "删除 Browser Address 的地址" });
  await confirmation.getByRole("button", { name: "删除这个地址" }).click();
  await expect(createdAddress).toHaveCount(0);
  await expect(originalAddress).toContainText("默认地址");

  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL("/");
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader-two@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page.getByRole("heading", { name: "Second Reader" })).toBeVisible();
  await page.goto("/account/addresses");
  await expect(page.getByText("Second Customer")).toBeVisible();
  await expect(page.getByText("Test Customer")).toHaveCount(0);
  await expect(page.getByText("中山路 2 号")).toBeVisible();

  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL("/");
  await loginCustomer(page);
  await page.goto("/account/addresses");
  await expect(page.getByText("Test Customer")).toBeVisible();
  await expect(page.getByText("Second Customer")).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);

  expect(addressResponses.length).toBeGreaterThanOrEqual(10);
  expect(addressResponses.every((response) => response.status === 200)).toBe(true);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([]);
});

test("shopping bag isolates owners and recovers state-set and merge response loss", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const failedCartRequests: Array<{ method: string; path: string }> = [];
  const cartResponses: Array<{ method: string; path: string; status: number }> = [];
  const mergeRequests: Array<{ key: string | null; body: unknown }> = [];
  let loseNextPutResponse = true;
  let loseNextMergeResponse = true;

  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/v1/trade/cart/guest-merge") {
      mergeRequests.push({
        key: request.headers()["idempotency-key"] ?? null,
        body: request.postDataJSON(),
      });
    }
  });
  page.on("requestfailed", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/v1/trade/cart/")) {
      failedCartRequests.push({
        method: request.method(),
        path: url.pathname,
      });
    }
  });
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/v1/trade/cart/")) {
      cartResponses.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });
  await page.route("**/api/v1/trade/cart/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "PUT" && loseNextPutResponse) {
      loseNextPutResponse = false;
      await route.fetch();
      await route.abort("failed");
      return;
    }
    if (
      request.method() === "POST"
      && path === "/api/v1/trade/cart/guest-merge"
      && loseNextMergeResponse
    ) {
      loseNextMergeResponse = false;
      await route.fetch();
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader-two@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page.getByRole("heading", { name: "Second Reader" })).toBeVisible();
  await page.goto("/bag");

  let accountRow = page.getByRole("article").filter({ hasText: "青灰随行本" });
  await expect(accountRow).toBeVisible();
  await expect(page.getByText("帆布通勤袋")).toHaveCount(0);

  const quantity = accountRow.getByLabel("数量");
  await quantity.fill("3");
  await quantity.press("Tab");
  await expect(page.getByText("购物车修改结果尚未确认。")).toBeVisible();
  await expect(quantity).toHaveValue("1");
  await page.getByRole("button", { name: "先重新读取" }).click();
  accountRow = page.getByRole("article").filter({ hasText: "青灰随行本" });
  await expect(accountRow.getByLabel("数量")).toHaveValue("3");

  const selected = accountRow.getByLabel("纳入结算");
  await selected.uncheck();
  await expect(page.getByText("已选 0 件。")).toBeVisible();
  await expect(page.getByRole("link", { name: "查看结算草稿 →" })).toHaveCount(0);
  await selected.check();
  await expect(page.getByText("已选 3 件。")).toBeVisible();

  await accountRow.getByRole("button", { name: "移出" }).click();
  let removal = accountRow.getByRole("group", { name: "从账户购物车移出 青灰随行本" });
  await removal.getByRole("button", { name: "保留" }).click();
  await expect(accountRow).toBeVisible();
  await accountRow.getByRole("button", { name: "移出" }).click();
  removal = accountRow.getByRole("group", { name: "从账户购物车移出 青灰随行本" });
  await removal.getByRole("button", { name: "确认移出" }).click();
  await expect(accountRow).toHaveCount(0);

  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL("/");
  await expect(page.getByRole("link", { name: "登录", exact: true })).toBeVisible();
  await page.goto("/products/2079000000000000001");
  const addToBag = page.getByRole("button", { name: "加入购物袋" });
  await addToBag.click();
  await addToBag.click();
  await addToBag.click();
  await page.goto("/bag");
  let guestRow = page.getByRole("article").filter({ hasText: "帆布通勤袋" });
  await expect(guestRow.getByLabel("数量")).toHaveValue("3");
  await guestRow.getByRole("button", { name: "移出" }).click();
  await page.getByRole("button", { name: "撤销" }).click();
  guestRow = page.getByRole("article").filter({ hasText: "帆布通勤袋" });
  await expect(guestRow.getByLabel("数量")).toHaveValue("3");

  await page.getByRole("link", { name: "登录并安全合并 →" }).click();
  await page.getByLabel("邮箱").fill("reader-two@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL("/bag");
  await expect(page.getByText("合并结果暂时未知")).toBeVisible();
  await expect(page.getByRole("heading", { name: "尚未确认移除的游客商品" }))
    .toBeVisible();
  accountRow = page.getByRole("article").filter({ hasText: "帆布通勤袋" });
  await expect(accountRow.getByLabel("数量")).toHaveValue("3");
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem("plain-journal:guest-bag-merge:v1"))).not.toBeNull();

  await page.getByRole("button", { name: "使用原重试键再次确认" }).click();
  await expect(page.getByRole("heading", { name: "尚未确认移除的游客商品" }))
    .toHaveCount(0);
  await expect(accountRow.getByLabel("数量")).toHaveValue("3");
  await expect.poll(() => page.evaluate(() => ({
    bag: localStorage.getItem("plain-journal:guest-bag:v1"),
    pending: localStorage.getItem("plain-journal:guest-bag-merge:v1"),
  }))).toEqual({ bag: "[]", pending: null });
  await expectNoSeriousAccessibilityViolations(page);

  expect(mergeRequests).toHaveLength(2);
  expect(mergeRequests[0]?.key).toBeTruthy();
  expect(mergeRequests[1]).toEqual(mergeRequests[0]);
  expect(failedCartRequests).toEqual([
    { method: "PUT", path: "/api/v1/trade/cart/items/2079000000000002011" },
    { method: "POST", path: "/api/v1/trade/cart/guest-merge" },
  ]);
  expect(cartResponses.every((response) => response.status === 200)).toBe(true);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors.filter((message) =>
    message.includes("Failed to load resource: net::ERR_FAILED"))).toHaveLength(2);
  expect(consoleErrors.filter((message) =>
    !message.includes("Failed to load resource: net::ERR_FAILED"))).toEqual([]);
});

test("checkout composes owner facts and rechecks three authoritative domains", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const checkoutResponses: Array<{ method: string; path: string; status: number }> = [];
  const previewBodies: Array<Record<string, unknown>> = [];
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
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (
      url.pathname.startsWith("/api/v1/identity/addresses")
      || url.pathname.startsWith("/api/v1/trade/cart/items")
      || url.pathname.startsWith("/api/v1/marketing/")
      || url.pathname.startsWith("/api/v1/catalog/products/")
      || url.pathname.startsWith("/api/v1/inventory/stocks/")
    ) {
      checkoutResponses.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });

  await loginCustomer(page);
  await page.goto("/checkout");
  await expect(page.getByRole("heading", { name: "订单确认", level: 1 })).toBeVisible();
  await expect(page.getByText("Test Customer")).toBeVisible();
  await expect(page.getByText("帆布通勤袋")).toBeVisible();
  await expect(page.getByText("¥378.00", { exact: true }).first()).toBeVisible();

  await page.getByLabel(/COUPON-10/u).check();
  await page.getByRole("button", { name: "重新试算金额 →" }).click();
  await expect(page.getByText("¥368.00", { exact: true })).toBeVisible();

  await page.getByRole("button", {
    name: "核对实时价格、库存与优惠",
  }).click();
  await expect(page.getByText("权威核对已完成")).toBeVisible();
  await expect(page.getByText(/可用 18 件/u)).toBeVisible();
  await expect(page.getByRole("button", { name: "以当前事实提交订单 →" }))
    .toBeEnabled();
  await expectNoSeriousAccessibilityViolations(page);

  expect(previewBodies).toHaveLength(3);
  expect(previewBodies[1]).toMatchObject({
    originalAmount: "378.00",
    benefitNos: ["BEN-001"],
  });
  expect(previewBodies[2]).toMatchObject({
    originalAmount: "378.00",
    benefitNos: ["BEN-001"],
  });
  expect(checkoutResponses).toEqual(expect.arrayContaining([
    { method: "GET", path: "/api/v1/identity/addresses", status: 200 },
    { method: "GET", path: "/api/v1/trade/cart/items", status: 200 },
    { method: "GET", path: "/api/v1/marketing/benefits", status: 200 },
    {
      method: "GET",
      path: "/api/v1/catalog/products/2079000000000000001",
      status: 200,
    },
    {
      method: "GET",
      path: "/api/v1/inventory/stocks/2079000000000000011",
      status: 200,
    },
  ]));
  expect(checkoutResponses.every((response) => response.status === 200)).toBe(true);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([]);
});

test("order and Payment workspace recovers a lost create response without opening cancellation", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const paymentRequests: Array<{
    method: string;
    path: string;
    key: string | null;
  }> = [];
  const paymentResponses: Array<{ path: string; status: number }> = [];
  const failedRequests: Array<{ method: string; path: string }> = [];
  let loseCreateResponse = true;
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/v1/payment/payments")) {
      paymentRequests.push({
        method: request.method(),
        path: url.pathname,
        key: request.headers()["idempotency-key"] ?? null,
      });
    }
  });
  page.on("requestfailed", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/v1/payment/payments")) {
      failedRequests.push({
        method: request.method(),
        path: url.pathname,
      });
    }
  });
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/v1/payment/payments")) {
      paymentResponses.push({
        path: url.pathname,
        status: response.status(),
      });
    }
  });
  await page.route("**/api/v1/payment/payments", async (route) => {
    if (route.request().method() === "POST" && loseCreateResponse) {
      loseCreateResponse = false;
      await route.fetch();
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await loginCustomer(page);
  await page.goto("/orders");
  await expect(page.getByRole("heading", { name: "我的订单", level: 1 })).toBeVisible();
  const pendingOrder = page.getByRole("article").filter({
    hasText: "ORD2079000000000008001",
  });
  await expect(pendingOrder).toContainText("待支付");
  await pendingOrder.getByRole("link", { name: "查看并可取消" }).click();

  await expect(page.getByRole("heading", { name: "订单详情", level: 1 })).toBeVisible();
  await expect(page.getByRole("button", { name: "取消订单" })).toBeVisible();
  await page.getByRole("button", { name: "创建支付单" }).click();
  await expect(page.getByText("支付单已保存，正在等待渠道返回最终结果。"))
    .toBeVisible();
  await expect(page.getByText("支付结果正在确认")).toBeVisible();
  await expect(page.getByRole("button", { name: "取消订单" })).toHaveCount(0);
  await expect(page.getByText(/为避免并发，本页暂不开放顾客取消/u)).toBeVisible();
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem(
      "plain-journal:pending-payment:v2:2079000000000000999",
    ))).toBeNull();
  await expectNoSeriousAccessibilityViolations(page);

  expect(paymentRequests.filter((request) => request.method === "POST")).toHaveLength(1);
  expect(paymentRequests.find((request) => request.method === "POST")?.key)
    .toMatch(/^payment:/u);
  expect(paymentRequests.some((request) =>
    request.path.startsWith("/api/v1/payment/payments/by-idempotency-key/")))
    .toBe(true);
  expect(failedRequests).toEqual([{
    method: "POST",
    path: "/api/v1/payment/payments",
  }]);
  expect(paymentResponses.filter((response) => response.status === 404)).toEqual([{
    path: "/api/v1/payment/payments/by-order/ORD2079000000000008001",
    status: 404,
  }]);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors.filter((message) =>
    !message.includes("Failed to load resource: net::ERR_FAILED")
    && !message.includes("404 (Not Found)"))).toEqual([]);
});

test("Payment unknown result unlocks safe retry and converges on authority", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const postKeys: Array<string | null> = [];
  const paymentResponses: Array<{ method: string; path: string; status: number }> = [];
  let postAttempts = 0;
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("response", (response) => {
    const request = response.request();
    const path = new URL(response.url()).pathname;
    if (path.startsWith("/api/v1/payment/payments")) {
      paymentResponses.push({
        method: request.method(),
        path,
        status: response.status(),
      });
    }
  });
  await page.route("**/api/v1/payment/payments/by-order/**", async (route) => {
    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({
        code: "RESOURCE_NOT_FOUND",
        message: "payment not found",
        data: null,
        timestamp: "2026-08-02T00:00:00Z",
      }),
    });
  });
  await page.route("**/api/v1/payment/payments/by-idempotency-key/**", async (route) => {
    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({
        code: "RESOURCE_NOT_FOUND",
        message: "payment not found",
        data: null,
        timestamp: "2026-08-02T00:00:00Z",
      }),
    });
  });
  await page.route("**/api/v1/payment/payments", async (route) => {
    postAttempts += 1;
    postKeys.push(route.request().headers()["idempotency-key"] ?? null);
    if (postAttempts === 1) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "REMOTE_DEPENDENCY_UNAVAILABLE",
          message: "response unavailable",
          data: null,
          timestamp: "2026-08-02T00:00:00Z",
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: "OK",
        message: "success",
        data: {
          paymentNo: "PAY2079000000000008009",
          orderNo: "ORD2079000000000008001",
          channel: "MOCK",
          status: "PROCESSING",
          amount: "398.00",
          channelTransactionNo: null,
          paidAt: null,
          createdAt: "2026-08-02T00:00:01Z",
          updatedAt: "2026-08-02T00:00:01Z",
        },
        timestamp: "2026-08-02T00:00:01Z",
      }),
    });
  });

  await loginCustomer(page);
  await page.goto("/orders/ORD2079000000000008001");
  await expect(page.getByRole("button", { name: "创建支付单" })).toBeVisible();
  await page.getByRole("button", { name: "创建支付单" }).click();

  const retryButtons = page.getByRole("button", {
    name: "按原支付键查询并重试",
  });
  await expect(retryButtons).toHaveCount(1);
  await expect(retryButtons.first()).toBeEnabled();
  await expect(page.getByRole("button", { name: /正在确认/u })).toHaveCount(0);
  const pendingAfterUnknown = await page.evaluate(() =>
    localStorage.getItem(
      "plain-journal:pending-payment:v2:2079000000000000999",
    ));
  expect(pendingAfterUnknown).not.toBeNull();

  await retryButtons.first().click();

  await expect(page.getByText("支付结果正在确认")).toBeVisible();
  await expect(page.getByText("支付单已保存，正在等待渠道返回最终结果。"))
    .toBeVisible();
  await expect(retryButtons).toHaveCount(0);
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem(
      "plain-journal:pending-payment:v2:2079000000000000999",
    ))).toBeNull();
  await expect(page.getByRole("button", { name: "取消订单" })).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);

  expect(postKeys).toHaveLength(2);
  expect(postKeys[0]).toMatch(/^payment:/u);
  expect(postKeys[1]).toBe(postKeys[0]);
  expect(paymentResponses).toEqual(expect.arrayContaining([
    {
      method: "POST",
      path: "/api/v1/payment/payments",
      status: 503,
    },
    {
      method: "POST",
      path: "/api/v1/payment/payments",
      status: 200,
    },
  ]));
  expect(pageErrors).toEqual([]);
  expect(consoleErrors.filter((message) =>
    !message.includes("503 (Service Unavailable)")
    && !message.includes("404 (Not Found)"))).toEqual([]);
});

test("customer theme, benefit and reverse-transaction flow stays factual", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await loginCustomer(page);
  await expect(page.getByRole("heading", { name: "Reader" })).toBeVisible();

  await page.getByRole("link", { name: /优惠权益/ }).click();
  await expect(page.getByRole("heading", { name: "优惠权益" })).toBeVisible();
  await expect(page.getByText("1 份当前可用")).toBeVisible();
  await expect(page.getByText("BEN-001")).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  await page.goto("/index");
  await expect(page.getByLabel("青荷")).toBeChecked();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");
  await page.getByLabel("素白").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "subai");
  await page.getByLabel("青荷").check();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");

  await page.goto("/after-sales");
  await expect(page.getByRole("heading", { name: "售后服务" })).toBeVisible();
  await page.getByRole("link", { name: /ORD2079000000000003002/ }).click();
  await expect(page.getByRole("heading", { name: "售后详情" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "当前进度" })).toBeVisible();
  await expect(page.getByText("当前处理方")).toBeVisible();
  await expect(page.getByText("顾客", { exact: true })).toBeVisible();
  await expect(page.getByText(/提交真实寄回信息/u)).toBeVisible();
  await expect(
    page.locator(".after-sale-boundary dd").filter({ hasText: "WAIT_SHIPMENT" }),
  ).toBeVisible();
  await page.getByLabel("承运商代码").fill("SF");
  await page.getByLabel("运单号").fill("SF1234567890");
  await page.getByRole("button", { name: "提交寄回信息" }).click();
  const afterSaleBoundary = page.locator(".after-sale-boundary");
  await expect(afterSaleBoundary.locator("dl div").filter({ hasText: "售后" }))
    .toContainText("RETURNING");
  await expect(afterSaleBoundary.locator("dl div").filter({ hasText: "退货" }))
    .toContainText("RETURNING");
  await expect(page.getByText("承运商与 Fulfillment", { exact: true })).toBeVisible();
  await expect(page.getByText("SF / SF1234567890")).toBeVisible();
  await expect(afterSaleBoundary.locator("dl div").filter({ hasText: "退款" }))
    .toContainText("PROCESSING");
  await expectNoSeriousAccessibilityViolations(page);

  expect(pageErrors).toEqual([]);
});

test("admin role gate exposes only real owner-domain workspaces", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("http://127.0.0.1:18201/login");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await expect(page).toHaveURL("http://127.0.0.1:18201/");
  await expect(
    page.getByRole("heading", { name: "工作区", level: 1 }),
  ).toBeVisible();

  await page.getByRole("link", {
    name: "履约与退货",
    exact: true,
  }).click();
  await expect(page.getByRole("heading", { name: "履约与退货" })).toBeVisible();
  await expect(page.getByText(
    "MySQL 保存物流与退货事实；Redis GEO 仅是可重建投影。",
    { exact: false },
  )).toBeVisible();
  await page.getByRole("button", { name: "查询范围内位置" }).click();
  const geoResult = page.getByRole("article").filter({
    has: page.getByRole("heading", { name: "杭州市" }),
  });
  await expect(geoResult).toContainText("FUL2079000000000004001");
  await expect(geoResult).toContainText("0 m");
  await page.getByRole("button", { name: "从 MySQL 重建 Redis GEO" }).click();
  await expect(page.getByText("Redis GEO 已从 MySQL 投影重建 1/1 条位置。"))
    .toBeVisible();
  await page.getByRole("button", { name: "开始拣货" }).click();
  await expect(
    page.locator(".fulfillment-record .status-label").filter({ hasText: "PICKING" }),
  ).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  await page.getByRole("link", { name: "补偿与对账" }).click();
  await expect(page.getByRole("heading", { name: "补偿与对账" })).toBeVisible();
  await expect(page.getByText(
    "四域对账保持只读；补偿命令只推进合法状态机。",
    { exact: false },
  )).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  expect(pageErrors).toEqual([]);
});
