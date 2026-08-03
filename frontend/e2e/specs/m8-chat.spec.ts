import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function expectNoSeriousAccessibilityViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter((violation) =>
    violation.impact === "serious" || violation.impact === "critical");
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

async function installFakeWebSocket(page: Page) {
  await page.addInitScript(() => {
    class ControlledWebSocket {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSING = 2;
      static readonly CLOSED = 3;

      readonly url: string;
      readonly protocol = "";
      readonly extensions = "";
      readonly bufferedAmount = 0;
      readonly binaryType = "blob";
      readyState = ControlledWebSocket.CONNECTING;
      onopen: ((event: Event) => void) | null = null;
      onmessage: ((event: MessageEvent) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      onclose: ((event: CloseEvent) => void) | null = null;

      constructor(url: string | URL) {
        this.url = String(url);
        globalThis.setTimeout(() => {
          this.readyState = ControlledWebSocket.OPEN;
          this.onopen?.(new Event("open"));
        }, 0);
      }

      send(_data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
        // REST/MySQL remains authoritative; this controlled socket only proves UI lifecycle.
      }

      close(code = 1000, reason = ""): void {
        this.readyState = ControlledWebSocket.CLOSED;
        this.onclose?.(new CloseEvent("close", { code, reason, wasClean: true }));
      }

      addEventListener(): void {
        // The application uses onopen/onmessage/onclose handlers.
      }

      removeEventListener(): void {
        // The application uses onopen/onmessage/onclose handlers.
      }

      dispatchEvent(): boolean {
        return true;
      }
    }

    Object.defineProperty(globalThis, "WebSocket", {
      configurable: true,
      value: ControlledWebSocket,
    });
  });
}

async function loginCustomer(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("reader@example.com");
  await page.getByLabel("密码").fill("ReaderPass123");
  await page.getByRole("button", { name: "登录 →" }).click();
  await expect(page).toHaveURL(/\/account$/);
}

test.describe.configure({ mode: "serial" });

test("customer creates a durable text conversation without exposing attachments", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installFakeWebSocket(page);
  await loginCustomer(page);

  await page.goto("/support");
  await expect(page.getByRole("heading", { name: "联系素简记" })).toBeVisible();
  await expect(page.getByText("实时更新可用", { exact: true })).toBeVisible();
  await page.getByLabel("这次需要什么帮助？").fill("M8.6 浏览器工作区验证");
  await page.getByRole("button", { name: "建立会话" }).click();
  await expect(page).toHaveURL(/\/support\/2079000000000005002$/);

  await page.getByLabel("回复内容").fill("这是一条由顾客通过可靠 REST 写入的文本消息。");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(
    page.getByText("这是一条由顾客通过可靠 REST 写入的文本消息。"),
  ).toBeVisible();
  await expect(page.getByText(/实时连接不是成功凭据/)).toBeVisible();
  await expect(page.getByRole("button", { name: /上传|附件|下载/ })).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);
  expect(pageErrors).toEqual([]);
});

test("support agent claims before reading and replies through the owner-domain API", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installFakeWebSocket(page);

  await page.goto("http://127.0.0.1:18201/login");
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await expect(page).toHaveURL("http://127.0.0.1:18201/");
  await page.goto("http://127.0.0.1:18201/chat");

  await expect(page.getByRole("heading", { name: "客服会话" })).toBeVisible();
  await expect(page.getByText("实时可用", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: /M8.6 浏览器工作区验证/ }).click();
  await expect(
    page.getByText("这是一条由顾客通过可靠 REST 写入的文本消息。"),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "认领并读取会话" }).click();
  await expect(
    page.getByText("这是一条由顾客通过可靠 REST 写入的文本消息。"),
  ).toBeVisible();

  await page.getByLabel("回复顾客").fill("已确认：当前页面只开放文本会话。");
  await page.getByRole("button", { name: "发送客服回复" }).click();
  await expect(page.getByText("已确认：当前页面只开放文本会话。")).toBeVisible();
  await expect(page.getByRole("button", { name: /上传|附件|下载/ })).toHaveCount(0);

  await page.getByRole("button", { name: "结束会话" }).click();
  await expect(page.getByText("CLOSED", { exact: true })).toBeVisible();
  await expect(page.getByLabel("回复顾客")).toHaveCount(0);
  await expect(page.getByText("该会话已经关闭，历史消息保留只读。")).toBeVisible();

  await page.reload();
  await expect(page.getByText("已确认：当前页面只开放文本会话。")).toBeVisible();
  await expect(page.getByText("CLOSED", { exact: true })).toBeVisible();
  await expect(page.getByLabel("回复顾客")).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);
  expect(pageErrors).toEqual([]);
});

test("customer can reopen a closed conversation URL as read-only history", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installFakeWebSocket(page);
  await loginCustomer(page);

  await page.goto("/support/2079000000000005002");
  await expect(page.getByText("已确认：当前页面只开放文本会话。")).toBeVisible();
  await expect(page.getByText("已结束", { exact: true })).toBeVisible();
  await expect(page.getByLabel("回复内容")).toHaveCount(0);
  await expect(page.getByText("历史消息仍可读取，但不能继续发送。")).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);
  expect(pageErrors).toEqual([]);
});
