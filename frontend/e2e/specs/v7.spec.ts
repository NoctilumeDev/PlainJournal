import { expect, test, type Page } from "@playwright/test";

const completedOrderNo = "ORD2079000000000007001";
const productId = "2079000000000000001";

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/u);
}

function captureImages(page: Page) {
  const responses: Array<{
    path: string;
    status: number;
    contentType: string;
  }> = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (
      response.request().resourceType() === "image"
      && /\.(?:avif|webp|png)$/u.test(url.pathname)
    ) {
      responses.push({
        path: url.pathname,
        status: response.status(),
        contentType: response.headers()["content-type"] ?? "",
      });
    }
  });
  return responses;
}

test.describe.configure({ mode: "serial" });

test("V7 storefront selects AVIF responsive catalog and fulfillment images", async ({
  page,
}) => {
  const responses = captureImages(page);
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  const featuredImage = page.locator(".home-featured img");
  await expect(featuredImage).toBeVisible();
  await expect.poll(() => featuredImage.evaluate((image) => ({
    complete: image.complete,
    naturalWidth: image.naturalWidth,
    currentSrc: image.currentSrc,
    fetchPriority: image.fetchPriority,
  }))).toMatchObject({
    complete: true,
    fetchPriority: "high",
  });
  const featuredCurrentSrc = await featuredImage.evaluate((image) => image.currentSrc);
  expect(new URL(featuredCurrentSrc).pathname).toMatch(
    /\/images\/catalog\/canvas-commuter-tote-(?:480|800|1122)\.avif$/u,
  );

  await page.goto(`/products/${productId}`);
  const detailImage = page.locator(".product-media__main img");
  await expect(detailImage).toBeVisible();
  await expect.poll(() => detailImage.evaluate((image) =>
    image.complete && image.naturalWidth > 0)).toBe(true);
  expect(new URL(await detailImage.evaluate((image) => image.currentSrc)).pathname)
    .toMatch(/canvas-commuter-tote-(?:480|800|1122)\.avif$/u);

  await loginCustomer(page);
  await page.goto(`/orders/${completedOrderNo}`);
  const fulfillmentImage = page.locator(".fulfillment-visual img");
  await fulfillmentImage.scrollIntoViewIfNeeded();
  await expect.poll(() => fulfillmentImage.evaluate((image) =>
    image.complete && image.naturalWidth > 0)).toBe(true);
  expect(new URL(await fulfillmentImage.evaluate((image) => image.currentSrc)).pathname)
    .toMatch(/qinghe-parcel-route-(?:640|1024|1672)\.avif$/u);

  expect(responses).toEqual(expect.arrayContaining([
    expect.objectContaining({
      path: expect.stringMatching(/canvas-commuter-tote-(?:480|800|1122)\.avif$/u),
      status: 200,
      contentType: expect.stringContaining("image/avif"),
    }),
    expect.objectContaining({
      path: expect.stringMatching(/qinghe-parcel-route-(?:640|1024|1672)\.avif$/u),
      status: 200,
      contentType: expect.stringContaining("image/avif"),
    }),
  ]));
  expect(responses.some((response) =>
    /(?:canvas-commuter-tote|qinghe-parcel-route)\.png$/u.test(response.path)))
    .toBe(false);
  expect(consoleErrors).toEqual([]);
});

test("V7 admin serves the shared catalog AVIF variants without duplicating sources", async ({
  page,
}) => {
  const responses = captureImages(page);
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("http://127.0.0.1:18201/login");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await page.goto("http://127.0.0.1:18201/catalog");

  const image = page.locator(".catalog-product__media img").first();
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((element) =>
    element.complete && element.naturalWidth > 0)).toBe(true);
  expect(new URL(await image.evaluate((element) => element.currentSrc)).pathname)
    .toMatch(/canvas-commuter-tote-480\.avif$/u);
  expect(responses).toEqual(expect.arrayContaining([
    expect.objectContaining({
      path: "/images/catalog/canvas-commuter-tote-480.avif",
      status: 200,
      contentType: expect.stringContaining("image/avif"),
    }),
  ]));
  expect(responses.some((response) =>
    /canvas-commuter-tote\.png$/u.test(response.path))).toBe(false);
  expect(pageErrors).toEqual([]);
});
