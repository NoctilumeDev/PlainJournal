import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const customerId = "2079000000000000999";
const alternateCustomerId = "2079000000000002999";

function envelope(data: unknown, code = "OK", message = "success") {
  return {
    code,
    message,
    data,
    timestamp: "2026-08-03T00:00:00Z",
  };
}

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

      send(): void {
        // REST/MySQL remains authoritative.
      }

      close(code = 1000, reason = ""): void {
        this.readyState = ControlledWebSocket.CLOSED;
        this.onclose?.(new CloseEvent("close", { code, reason, wasClean: true }));
      }
    }

    Object.defineProperty(globalThis, "WebSocket", {
      configurable: true,
      value: ControlledWebSocket,
    });
  });
}

function notification(
  id: string,
  templateCode: string,
  referenceType: string,
  referenceNo: string,
  status: "UNREAD" | "READ",
) {
  return {
    id,
    templateCode,
    referenceType,
    referenceNo,
    title: templateCode,
    content: `${templateCode} ${referenceNo}`,
    status,
    readAt: status === "READ" ? "2026-08-03T01:00:00Z" : null,
    createdAt: id.endsWith("6")
      ? "2026-08-03T01:00:00Z"
      : "2026-08-03T00:00:00Z",
  };
}

test.describe.configure({ mode: "serial" });

test("V6.3 notification read uncertainty reconciles through the owner-domain list", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  const primary = notification(
    "9223372036854775806",
    "SHIPMENT_DISPATCHED",
    "ORDER",
    "ORD-V63-001",
    "UNREAD",
  );
  const older = notification(
    "9223372036854775805",
    "REFUND_SUCCEEDED",
    "REFUND",
    "REF-V63-002",
    "READ",
  );
  const alternate = notification(
    "9223372036854775701",
    "PAYMENT_SUCCEEDED",
    "ORDER",
    "ORD-V63-SECOND",
    "UNREAD",
  );
  let readRequests = 0;
  const notificationAuthorizations: string[] = [];
  const readPaths: string[] = [];

  await page.route("**/api/v1/notifications**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const authorization = request.headers()["authorization"] ?? "";
    notificationAuthorizations.push(authorization);

    if (url.pathname.endsWith("/unread-count")) {
      const secondOwner = authorization.includes("customer-two");
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope({
          count: secondOwner ? 1 : primary.status === "UNREAD" ? 1 : 0,
        })),
      });
      return;
    }
    if (request.method() === "POST" && url.pathname.endsWith("/read")) {
      readRequests += 1;
      readPaths.push(url.pathname);
      primary.status = "READ";
      primary.readAt = "2026-08-03T01:30:00Z";
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "SERVICE_UNAVAILABLE",
          "read response lost",
        )),
      });
      return;
    }
    if (request.method() === "GET" && url.pathname === "/api/v1/notifications") {
      const secondOwner = authorization.includes("customer-two");
      const data = secondOwner
        ? {
            items: [alternate],
            nextCursor: null,
            hasMore: false,
          }
        : url.searchParams.has("cursor")
          ? {
              items: [older],
              nextCursor: null,
              hasMore: false,
            }
          : {
              items: [primary],
              nextCursor: "older-page",
              hasMore: true,
            };
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(data)),
      });
      return;
    }
    await route.fallback();
  });

  await loginCustomer(page);
  await page.goto("/account/notifications");
  await expect(page.getByRole("heading", { name: "最近发生的事项" })).toBeVisible();
  await expect(page.getByText("订单已经发货")).toBeVisible();
  await page.getByRole("button", { name: "标记为已读" }).click();

  const unknown = page.locator(".notification-read-result.pj-status-notice--unknown");
  await expect(unknown).toContainText("已读结果待确认");
  await expect(page.getByText("未读", { exact: true })).toBeVisible();
  await expect(page.locator(".notification-feedback.pj-status-notice--success"))
    .toHaveCount(0);
  expect(readRequests).toBe(1);
  expect(readPaths).toEqual([
    "/api/v1/notifications/9223372036854775806/read",
  ]);

  await page.getByRole("button", { name: "重新读取通知事实" }).click();
  await expect(unknown).toHaveCount(0);
  await expect(page.getByText("已读", { exact: true })).toBeVisible();
  await expect(page.getByText("已从通知服务确认这条通知已经读过。"))
    .toBeVisible();
  await page.getByRole("button", { name: "读取更早通知" }).click();
  await expect(page.getByText("退款已经完成")).toBeVisible();

  await page.goto("/account");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL(/\/$/u);
  await loginCustomer(page, "reader-two@example.com");
  await page.goto("/account/notifications");
  await expect(page.getByRole("link", { name: /ORD-V63-SECOND/u })).toBeVisible();
  await expect(page.getByText("ORD-V63-001")).toHaveCount(0);
  expect(notificationAuthorizations)
    .toContain("Bearer browser-customer-access-token-rotated");
  expect(notificationAuthorizations)
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

test("V6.3 Chat separates realtime, read failure, attachment quarantine and send uncertainty", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  await installFakeWebSocket(page);
  const sendBodies: Array<Record<string, unknown>> = [];
  let sendAttempts = 0;
  const conversationId = "2079000000000005001";
  const agentMessage = {
    id: "2079000000000005199",
    conversationId,
    senderId: "2079000000000001999",
    clientMessageId: "chat:message:agent-v63",
    sequence: 2,
    messageType: "TEXT",
    content: "客服回复：附件仍需完成授权与扫描后才能开放。",
    attachments: [{
      id: "2079000000000005299",
      fileName: "quarantined-note.txt",
      mimeType: "text/plain",
      sizeBytes: 32,
    }],
    status: "DELIVERED",
    createdAt: "2026-08-03T01:00:00Z",
  };

  await page.route(
    `**/api/v1/chat/conversations/${conversationId}/messages**`,
    async (route) => {
      const request = route.request();
      if (request.method() === "GET") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(envelope({
            items: [agentMessage],
            nextBeforeSequence: null,
            hasMore: false,
          })),
        });
        return;
      }
      const input = request.postDataJSON() as Record<string, unknown>;
      sendBodies.push(input);
      sendAttempts += 1;
      if (sendAttempts === 1) {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify(envelope(
            null,
            "SERVICE_UNAVAILABLE",
            "send response lost",
          )),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope({
          id: "2079000000000005300",
          conversationId,
          senderId: customerId,
          clientMessageId: input.clientMessageId,
          sequence: 3,
          messageType: "TEXT",
          content: input.content,
          attachments: [],
          status: "STORED",
          createdAt: "2026-08-03T01:05:00Z",
        })),
      });
    },
  );
  await page.route(
    `**/api/v1/chat/conversations/${conversationId}/read`,
    async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(envelope(
          null,
          "SERVICE_UNAVAILABLE",
          "read response lost",
        )),
      });
    },
  );

  await loginCustomer(page);
  await page.goto(`/support/${conversationId}`);
  await expect(page.getByText("实时更新可用", { exact: true })).toBeVisible();
  await expect(page.getByText("已读位置未能确认")).toBeVisible();
  await expect(page.getByText("附件仍在安全边界内")).toBeVisible();
  await expect(page.getByRole("button", { name: /上传|附件|下载/ })).toHaveCount(0);

  await page.getByLabel("回复内容").fill("这条消息必须使用同一个客户端消息键恢复。");
  await page.getByRole("button", { name: "发送消息" }).click();
  const unknown = page.locator(".support-send-result.pj-status-notice--unknown");
  await expect(unknown).toContainText("消息发送结果待确认");
  await expect(page.getByLabel("回复内容"))
    .toHaveValue("这条消息必须使用同一个客户端消息键恢复。");
  await page.getByRole("button", { name: "使用原消息键查询并重试" }).click();
  await expect(unknown).toHaveCount(0);
  await expect(page.getByText("这条消息必须使用同一个客户端消息键恢复。"))
    .toBeVisible();
  expect(sendBodies).toHaveLength(2);
  expect(sendBodies[1]?.clientMessageId).toBe(sendBodies[0]?.clientMessageId);
  expect(sendBodies[1]?.content).toBe(sendBodies[0]?.content);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.3 Chat never renders another account's unresolved message content", async ({
  page,
}) => {
  const diagnostics = observeDiagnostics(page);
  await installFakeWebSocket(page);
  await page.addInitScript(() => {
    localStorage.setItem(
      "plain-journal:customer-chat-pending-send:v1",
      JSON.stringify({
        userId: "2079000000000000999",
        conversationId: "2079000000000005001",
        clientMessageId: "chat:message:foreign",
        content: "PRIVATE-CONTENT-FROM-FIRST-OWNER",
        createdAt: "2026-08-03T00:00:00Z",
      }),
    );
  });

  await loginCustomer(page, "reader-two@example.com");
  await page.goto("/support");

  await expect(page.getByText("这个设备上有另一账户的待确认操作"))
    .toBeVisible();
  await expect(page.getByText("PRIVATE-CONTENT-FROM-FIRST-OWNER")).toHaveCount(0);
  await expect(page.getByLabel("回复内容")).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
