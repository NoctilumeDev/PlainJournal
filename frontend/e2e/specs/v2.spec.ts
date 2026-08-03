import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

async function expectNoPageOverflow(page: Page) {
  expect(await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))).toEqual({
    clientWidth: 320,
    scrollWidth: 320,
  });
}

test.describe.configure({ mode: "serial" });

test("shared storefront shell keeps theme, focus and motion semantics at 320px", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/login");

  await expect(page.locator("html")).toHaveAttribute("data-pj-theme", "qinghe");
  await expect(page.locator("main")).toHaveCount(1);
  await expectNoPageOverflow(page);

  await page.keyboard.press("Tab");
  await expect(page.locator(".pj-skip-link")).toBeFocused();

  const reducedDuration = await page.locator("html").evaluate((element) =>
    getComputedStyle(element).transitionDuration);
  expect(reducedDuration).toContain("0.001s");

  const qingheFacts = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement);
    return {
      page: style.getPropertyValue("--pj-surface-page").trim(),
      danger: style.getPropertyValue("--pj-status-danger-text").trim(),
      unknown: style.getPropertyValue("--pj-status-unknown-text").trim(),
    };
  });

  await page.goto("/index");
  await page.getByLabel("素白").check();
  const subaiFacts = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement);
    return {
      page: style.getPropertyValue("--pj-surface-page").trim(),
      danger: style.getPropertyValue("--pj-status-danger-text").trim(),
      unknown: style.getPropertyValue("--pj-status-unknown-text").trim(),
    };
  });

  expect(subaiFacts.page).not.toBe(qingheFacts.page);
  expect(subaiFacts.danger).toBe(qingheFacts.danger);
  expect(subaiFacts.unknown).toBe(qingheFacts.unknown);
  await expectNoPageOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);

  await page.goto("/");
  const productImages = page.locator(".product-card__media img");
  await expect(productImages).toHaveCount(2);
  await productImages.nth(0).scrollIntoViewIfNeeded();
  await productImages.nth(1).scrollIntoViewIfNeeded();
  await expect.poll(() =>
    productImages.evaluateAll((images) =>
      images.every((image) =>
        image instanceof HTMLImageElement
        && image.complete
        && image.naturalWidth > 0))).toBe(true);
});

test("shared admin shell contains dense navigation without page overflow at 320px", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.goto("http://127.0.0.1:18201/login");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await page.getByRole("link", {
    name: "履约与退货",
    exact: true,
  }).click();

  await expect(page.getByRole("heading", { name: "履约与退货" })).toBeVisible();
  await expect(page.locator("main")).toHaveCount(1);
  await expectNoPageOverflow(page);
  expect(await page.locator(".admin-nav").evaluate((element) =>
    element.scrollWidth > element.clientWidth)).toBe(true);
  await expectNoSeriousAccessibilityViolations(page);
});
