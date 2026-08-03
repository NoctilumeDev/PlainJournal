import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const productId = "2079000000000000001";
const orderNo = "ORD2079000000000007001";
const seededReview = "这条评价包含需要平台核对的错误信息。";

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

async function loginAdmin(page: Page) {
  await page.goto("http://127.0.0.1:18201/login");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await expect(page).toHaveURL("http://127.0.0.1:18201/");
}

test.describe.configure({ mode: "serial" });

test("completed order submits one factual review and reports another review", async ({ page }) => {
  const pageErrors: string[] = [];
  const reviewCommandKeys: string[] = [];
  let loseCreateResponse = true;
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (request.method() === "POST" && url.pathname === "/api/v1/catalog/reviews") {
      reviewCommandKeys.push(request.headers()["idempotency-key"] ?? "");
    }
  });
  await page.route("**/api/v1/catalog/reviews", async (route) => {
    if (route.request().method() === "POST" && loseCreateResponse) {
      loseCreateResponse = false;
      await route.fetch();
      await route.abort("failed");
      return;
    }
    await route.continue();
  });
  await loginCustomer(page);

  await page.goto(`/orders/${orderNo}`);
  await expect(page.getByRole("heading", { name: "完成订单后再评价" })).toBeVisible();
  await page.getByLabel("评分").selectOption("5");
  await page.getByLabel("评价内容").fill("实物与订单快照一致，通勤使用一周后仍然稳定。");
  await page.getByRole("button", { name: "提交评价" }).click();
  await expect(page.getByText("已从 Catalog 评价资格恢复提交结果")).toBeVisible();
  await expect(page.getByText("已评价", { exact: true })).toBeVisible();
  expect(reviewCommandKeys).toHaveLength(1);
  expect(reviewCommandKeys[0]).toMatch(/^review:/);

  await page.goto(`/products/${productId}`);
  await expect(
    page.getByText("实物与订单快照一致，通勤使用一周后仍然稳定。"),
  ).toBeVisible();
  const target = page.getByRole("article").filter({ hasText: seededReview });
  await target.getByRole("button", { name: "举报" }).click();
  await target.getByLabel("举报原因").selectOption("FALSE_INFORMATION");
  await target.getByLabel("补充说明").fill("该描述与实际商品规格不一致，请核对。");
  await target.getByRole("button", { name: "保存举报" }).click();
  await expect(page.getByText("举报已保存")).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);
  expect(pageErrors).toEqual([]);
});

test("operator replies and upheld moderation removes review from public score", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await loginAdmin(page);
  await page.goto("http://127.0.0.1:18201/reviews");

  await expect(page.getByRole("heading", { name: "评价治理" })).toBeVisible();
  await expect(page.getByText(seededReview)).toBeVisible();
  await page.getByLabel("平台公开回复").fill("平台已核对商品规格与订单快照。");
  await page.getByRole("button", { name: "保存平台回复" }).click();
  await expect(
    page.locator(".review-command-notice.pj-status-notice--success"),
  ).toContainText("平台公开回复");

  await page.goto(`http://127.0.0.1:18200/products/${productId}`);
  await expect(page.getByText("平台已核对商品规格与订单快照。")).toBeVisible();

  await page.goto("http://127.0.0.1:18201/reviews");
  await page.getByLabel("审核结论").selectOption("UPHELD");
  await page.getByLabel("审核说明").fill("已核对不可变订单行和当前商品规格，举报成立。");
  await page.getByRole("button", { name: "提交审核结论" }).click();
  await expect(
    page.locator(".review-command-notice.pj-status-notice--success"),
  ).toContainText("评分汇总扣回一次");
  await expectNoSeriousAccessibilityViolations(page);

  await page.goto(`http://127.0.0.1:18200/products/${productId}`);
  await expect(page.getByText(seededReview)).toHaveCount(0);
  await expect(page.getByText("5.0")).toBeVisible();
  expect(pageErrors).toEqual([]);
});
