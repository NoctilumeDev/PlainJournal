import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const emailA = requiredEnvironment("PLAIN_JOURNAL_REAL_CART_EMAIL_A");
const emailB = requiredEnvironment("PLAIN_JOURNAL_REAL_CART_EMAIL_B");
const productId = requiredEnvironment("PLAIN_JOURNAL_REAL_CART_PRODUCT_ID");
const skuId = requiredEnvironment("PLAIN_JOURNAL_REAL_CART_SKU_ID");
const productTitle = requiredEnvironment("PLAIN_JOURNAL_REAL_CART_PRODUCT_TITLE");
const password = "FrontendCartPass123";

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is required for the real account-cart test.`);
  }
  return value;
}

async function login(page: Page, email: string, returnTo = "/account") {
  await page.goto(`/login?returnTo=${encodeURIComponent(returnTo)}`);
  await page.getByLabel("邮箱").fill(email);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(returnTo);
}

async function logout(page: Page) {
  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL("/");
}

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

test("real Gateway cart keeps owners isolated and recovers two lost responses", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const failedRequests: Array<{ method: string; path: string }> = [];
  const cartResponses: Array<{ method: string; path: string; status: number }> = [];
  const mergeRequests: Array<{ key: string | null; body: unknown }> = [];
  let loseNextMergeResponse = true;
  let loseNextPutResponse = true;

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
      failedRequests.push({
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
    if (
      request.method() === "POST"
      && path === "/api/v1/trade/cart/guest-merge"
      && loseNextMergeResponse
    ) {
      loseNextMergeResponse = false;
      const committed = await route.fetch();
      expect(committed.status()).toBe(200);
      await route.abort("failed");
      return;
    }
    if (
      request.method() === "PUT"
      && path === `/api/v1/trade/cart/items/${skuId}`
      && loseNextPutResponse
    ) {
      loseNextPutResponse = false;
      const committed = await route.fetch();
      expect(committed.status()).toBe(200);
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await login(page, emailA);
  await page.goto("/bag");
  let accountRow = page.getByRole("article").filter({ hasText: productTitle });
  await expect(accountRow.getByLabel("数量")).toHaveValue("2");

  await logout(page);
  await login(page, emailB);
  await page.goto("/bag");
  await expect(page.getByRole("article")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "先找到一件真正适合的商品。" }))
    .toBeVisible();

  await logout(page);
  await page.goto(`/products/${productId}`);
  await expect(page.getByRole("heading", { name: productTitle, level: 1 })).toBeVisible();
  const addToBag = page.getByRole("button", { name: "加入购物袋 →" });
  await addToBag.click();
  await addToBag.click();
  await addToBag.click();
  await page.goto("/bag");
  const guestRow = page.getByRole("article").filter({ hasText: productTitle });
  await expect(guestRow.getByLabel("数量")).toHaveValue("3");

  await page.getByRole("link", { name: "登录并安全合并 →" }).click();
  await page.getByLabel("邮箱").fill(emailB);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL("/bag");
  await expect(page.getByText("合并结果暂时未知")).toBeVisible();
  await expect(page.getByRole("heading", { name: "尚未确认移除的游客商品" }))
    .toBeVisible();
  accountRow = page.getByRole("article").filter({ hasText: productTitle });
  await expect(accountRow.getByLabel("数量")).toHaveValue("3");

  await page.getByRole("button", { name: "使用原重试键再次确认" }).click();
  await expect(page.getByRole("heading", { name: "尚未确认移除的游客商品" }))
    .toHaveCount(0);
  await expect(accountRow.getByLabel("数量")).toHaveValue("3");
  await expect.poll(() => page.evaluate(() => ({
    bag: localStorage.getItem("plain-journal:guest-bag:v1"),
    pending: localStorage.getItem("plain-journal:guest-bag-merge:v1"),
  }))).toEqual({ bag: "[]", pending: null });

  let quantity = accountRow.getByLabel("数量");
  await quantity.fill("4");
  await quantity.press("Tab");
  await expect(page.getByText("购物车修改结果尚未确认。")).toBeVisible();
  await expect(quantity).toHaveValue("3");
  await page.getByRole("button", { name: "先重新读取" }).click();
  accountRow = page.getByRole("article").filter({ hasText: productTitle });
  quantity = accountRow.getByLabel("数量");
  await expect(quantity).toHaveValue("4");

  const selected = accountRow.getByLabel("纳入结算");
  await selected.uncheck();
  await expect(page.getByText("已选 0 件。")).toBeVisible();
  await selected.check();
  await expect(page.getByText("已选 4 件。")).toBeVisible();

  await accountRow.getByRole("button", { name: "移出" }).click();
  await accountRow
    .getByRole("group", { name: `从账户购物车移出 ${productTitle}` })
    .getByRole("button", { name: "确认移出" })
    .click();
  await expect(accountRow).toHaveCount(0);

  await logout(page);
  await login(page, emailA);
  await page.goto("/bag");
  accountRow = page.getByRole("article").filter({ hasText: productTitle });
  await expect(accountRow.getByLabel("数量")).toHaveValue("2");
  await expectNoSeriousAccessibilityViolations(page);

  expect(mergeRequests).toHaveLength(2);
  expect(mergeRequests[0]?.key).toMatch(/^guest-merge:[A-Za-z0-9-]+$/u);
  expect(mergeRequests[1]).toEqual(mergeRequests[0]);
  expect(mergeRequests[0]?.body).toEqual({
    items: [{
      productId,
      skuId,
      quantity: 3,
    }],
  });
  expect(failedRequests).toEqual([
    { method: "POST", path: "/api/v1/trade/cart/guest-merge" },
    { method: "PUT", path: `/api/v1/trade/cart/items/${skuId}` },
  ]);
  expect(cartResponses.every((response) => response.status === 200)).toBe(true);
  expect(pageErrors).toEqual([]);
  expect(consoleErrors.filter((message) =>
    message.includes("Failed to load resource: net::ERR_FAILED"))).toHaveLength(2);
  expect(consoleErrors.filter((message) =>
    !message.includes("Failed to load resource: net::ERR_FAILED"))).toEqual([]);
});
