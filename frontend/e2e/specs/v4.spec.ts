import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const productId = "2079000000000000001";

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

function envelope(data: unknown) {
  return JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-02T00:00:00Z",
  });
}

function productSummary(index: number) {
  return {
    id: String(2079000000000000001n + BigInt(index)),
    title: `通勤选物 ${index + 1}`,
    subtitle: "保留材料、尺寸与使用边界的日常用品",
    category: {
      id: "2079000000000000101",
      parentId: null,
      name: "随身用品",
      slug: "carry",
      sortOrder: 1,
    },
    brand: {
      id: "2079000000000000201",
      name: "素简记",
      slug: "plain-journal",
    },
    minimumPrice: "189.00",
    coverUrl: "/images/catalog/canvas-commuter-tote.png",
  };
}

test.describe.configure({ mode: "serial" });

test("V4 catalog keeps category and pagination in the URL at desktop and 320px", async ({ page }) => {
  const diagnostics = observeDiagnostics(page);
  const requests: string[] = [];
  const summaries = Array.from({ length: 25 }, (_, index) => productSummary(index));
  await page.route("**/api/v1/catalog/products?*", async (route) => {
    const url = new URL(route.request().url());
    requests.push(`${url.pathname}?${url.searchParams.toString()}`);
    const selectedPage = Number(url.searchParams.get("page") ?? 1);
    const size = Number(url.searchParams.get("size") ?? 12);
    const offset = (selectedPage - 1) * size;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: envelope({
        items: summaries.slice(offset, offset + size),
        page: selectedPage,
        size,
        total: summaries.length,
      }),
    });
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/products?category=carry&page=2");
  await expect(page.getByRole("heading", { name: "随身用品", level: 1 })).toBeVisible();
  await expect(page.locator(".product-card")).toHaveCount(12);
  await expect(page.locator(".product-card h2")).toHaveCount(12);
  await expect(page.getByRole("link", { name: "随身用品", exact: true }))
    .toHaveAttribute("aria-current", "page");
  await expect(page.locator('a[rel="prev"]'))
    .toHaveAttribute("href", "/products?category=carry");
  await expect(page.locator('a[rel="next"]'))
    .toHaveAttribute("href", "/products?category=carry&page=3");
  await expect(page.getByRole("link", { name: "书写纸品", exact: true }))
    .toHaveAttribute("href", "/products?category=writing");
  await expectNoRootOverflow(page);

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  expect(requests).toContain(
    "/api/v1/catalog/products?page=2&size=12&categoryId=2079000000000000101",
  );
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test("V4 search preserves query pages and never hides the MySQL fallback", async ({ page }) => {
  const diagnostics = observeDiagnostics(page);
  const requests: string[] = [];
  const summaries = Array.from({ length: 25 }, (_, index) => productSummary(index));
  await page.route("**/api/v1/catalog/search/products?*", async (route) => {
    const url = new URL(route.request().url());
    requests.push(`${url.pathname}?${url.searchParams.toString()}`);
    const selectedPage = Number(url.searchParams.get("page") ?? 1);
    const size = Number(url.searchParams.get("size") ?? 12);
    const offset = (selectedPage - 1) * size;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: envelope({
        items: summaries.slice(offset, offset + size),
        page: selectedPage,
        size,
        matchedTotal: summaries.length,
        source: "MYSQL_FALLBACK",
        degraded: true,
      }),
    });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/search?q=通勤&page=2");
  await expect(page.getByRole("heading", { name: "你正在寻找什么？", level: 1 }))
    .toBeVisible();
  await expect(page.getByLabel("商品名称或用途")).toHaveValue("通勤");
  await expect(page.getByText("查找范围暂时收窄", { exact: true })).toBeVisible();
  await expect(page.locator('[data-search-source="MYSQL_FALLBACK"]')).toBeVisible();
  await expect(page.getByText("商品事实库的基础匹配", { exact: false })).toBeVisible();
  await expect(page.locator('a[rel="prev"]'))
    .toHaveAttribute("href", "/search?q=%E9%80%9A%E5%8B%A4");
  await expect(page.locator('a[rel="next"]'))
    .toHaveAttribute("href", "/search?q=%E9%80%9A%E5%8B%A4&page=3");
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  await page.getByLabel("商品名称或用途").fill("书写");
  await page.getByRole("button", { name: "显示结果" }).click();
  await expect(page).toHaveURL("/search?q=%E4%B9%A6%E5%86%99");

  expect(requests).toEqual(expect.arrayContaining([
    "/api/v1/catalog/search/products?q=%E9%80%9A%E5%8B%A4&page=2&size=12",
    "/api/v1/catalog/search/products?q=%E4%B9%A6%E5%86%99&page=1&size=12",
  ]));
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test("V4 index, image browsing and public reviews remain one factual discovery journey", async ({
  page,
  request,
}) => {
  const diagnostics = observeDiagnostics(page);
  const resetResponse = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/reviews/reset",
  );
  expect(resetResponse.ok()).toBe(true);
  await page.route(`**/api/v1/catalog/products/${productId}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: envelope({
        id: productId,
        title: "帆布通勤袋",
        subtitle: "轻量、耐用，保留材料本来的质感",
        description: "适合通勤与短途使用的克制日常用品。",
        status: "ACTIVE",
        version: 1,
        category: {
          id: "2079000000000000101",
          parentId: null,
          name: "随身用品",
          slug: "carry",
          sortOrder: 1,
        },
        brand: {
          id: "2079000000000000201",
          name: "素简记",
          slug: "plain-journal",
        },
        skus: [{
          id: "2079000000000000011",
          skuCode: "BAG-NATURAL-M",
          name: "自然色 / 中号",
          specJson: "{\"颜色\":\"自然色\",\"尺寸\":\"中号\"}",
          salePrice: "189.00",
          marketPrice: "219.00",
          status: "ACTIVE",
          version: 0,
        }],
        media: [
          {
            id: "2079000000000000301",
            skuId: null,
            objectKey: "demo/catalog/canvas-commuter-tote.png",
            mimeType: "image/png",
            sizeBytes: 1999183,
            sortOrder: 0,
            url: "/images/catalog/canvas-commuter-tote.png",
          },
          {
            id: "2079000000000000302",
            skuId: null,
            objectKey: "demo/catalog/canvas-commuter-tote.png",
            mimeType: "image/png",
            sizeBytes: 1999183,
            sortOrder: 1,
            url: "/images/catalog/canvas-commuter-tote.png?view=detail",
          },
        ],
      }),
    });
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/index");
  await expect(page.getByRole("heading", {
    name: "改变方向时，再打开导航。",
    level: 1,
  })).toBeVisible();
  await expect(page.getByText("内容待运营补齐")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "随身用品" }))
    .toHaveAttribute("href", "/products?category=carry");
  await expect(page.getByRole("link", { name: "书写纸品" }))
    .toHaveAttribute("href", "/products?category=writing");

  await page.goto(`/products/${productId}?image=2&from=index`);
  const mainImage = page.locator(".product-media__main img");
  await expect(mainImage).toHaveAttribute("alt", "帆布通勤袋，图片 2");
  await expect(mainImage).toHaveAttribute(
    "src",
    "/images/catalog/canvas-commuter-tote.png?view=detail",
  );
  await expect.poll(() => mainImage.evaluate((image) =>
    image instanceof HTMLImageElement
    && image.complete
    && image.naturalWidth > 0)).toBe(true);
  await page.getByRole("button", { name: "查看第 1 张图片" }).click();
  await expect(page).toHaveURL(`/products/${productId}?image=1&from=index`);
  await expect(page.getByRole("heading", { name: "评价", level: 2 })).toBeVisible();
  await expect(page.getByText("这条评价包含需要平台核对的错误信息。")).toBeVisible();
  await expectNoRootOverflow(page);

  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});
