import path from "node:path";

import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const refundNo = "RF-DEMO-NEEDS-ATTENTION";
const paymentNo = "PAY-DEMO-EXCEPTION";

function observeDiagnostics(page: Page) {
  const pageErrors: string[] = [];
  const consoleWarnings: string[] = [];
  const consoleErrors: string[] = [];
  const failedResponses: Array<{ status: number; url: string }> = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "warning") {
      consoleWarnings.push(message.text());
    }
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  page.on("response", (response) => {
    if (response.status() >= 400) {
      failedResponses.push({
        status: response.status(),
        url: response.url(),
      });
    }
  });
  return { pageErrors, consoleWarnings, consoleErrors, failedResponses };
}

function unexpectedConsoleErrors(errors: string[]) {
  return errors.filter((message) =>
    !message.includes("503 (Service Unavailable)"));
}

function unexpectedFailedResponses(
  responses: Array<{ status: number; url: string }>,
) {
  return responses.filter((response) => response.status !== 503);
}

async function loginAdmin(page: Page) {
  await page.goto(
    "http://127.0.0.1:18201/login?redirect=%2Fgovernance",
  );
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await expect(page).toHaveURL(/\/governance$/u);
}

async function loginAdminAt(page: Page, path: string) {
  await page.goto(
    `http://127.0.0.1:18201/login?redirect=${encodeURIComponent(path)}`,
  );
  await page.getByLabel("员工邮箱").fill("admin@example.com");
  await page.getByLabel("密码").fill("AdminPass123");
  await page.getByRole("button", { name: "登录工作区 →" }).click();
  await expect(page).toHaveURL(new RegExp(`${path.replace("/", "\\/")}$`, "u"));
}

async function resetGovernance(
  request: APIRequestContext,
  mode: "audit-confirmed" | "retry-required",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/governance/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
}

async function governanceDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/governance/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    refundCommands: Array<{
      commandId: string;
      referenceNo: string;
      reason: string;
      attempts: number;
    }>;
    exceptionCommands: Array<{
      commandId: string;
      referenceNo: string;
      reason: string;
      attempts: number;
    }>;
  };
}

async function resetFulfillment(
  request: APIRequestContext,
  mode: "trace-retry" | "resolve-retry",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/fulfillment/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
}

async function fulfillmentDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/fulfillment/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    commands: Array<{
      kind: "trace" | "resolve";
      referenceNo: string;
      commandKey: string;
      reason?: string;
      payload?: Record<string, unknown>;
      attempts: number;
    }>;
    fulfillment: {
      fulfillmentNo: string;
      status: string;
      traces: Array<{ externalEventId: string }>;
    };
  };
}

async function resetInventory(
  request: APIRequestContext,
  mode: "adjustment-retry" | "warehouse-authority",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/inventory/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    warehouseId: string;
    skuId: string;
  };
}

async function inventoryDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/inventory/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    commands: Array<{
      kind: "warehouse" | "adjustment";
      referenceNo: string;
      commandKey: string;
      payload: Record<string, unknown>;
      attempts: number;
      applied?: boolean;
    }>;
    warehouses: Array<{
      id: string;
      code: string;
      name: string;
    }>;
    stock: {
      warehouseId: string;
      skuId: string;
      onHand: number;
      reserved: number;
      available: number;
      version: number;
    };
  };
}

async function resetMarketing(
  request: APIRequestContext,
  mode: "grant-retry" | "rule-unknown",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/marketing/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    userId: string;
    ruleCode: string;
  };
}

async function marketingDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/marketing/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    commands: Array<{
      kind: "rule" | "grant";
      referenceNo: string;
      commandKey: string;
      userId?: string;
      ruleCode?: string;
      payload: Record<string, unknown>;
      attempts: number;
      benefitNo?: string;
    }>;
    rules: Array<{
      ruleCode: string;
      name: string;
    }>;
    benefits: Array<{
      benefitNo: string;
      userId: string;
      ruleCode: string;
      status: string;
    }>;
  };
}

async function resetAdminAfterSale(
  request: APIRequestContext,
  mode: "normal" | "commit-lost" | "retry-required",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/admin-after-sale/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    afterSaleNo: string;
  };
}

async function adminAfterSaleDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/admin-after-sale/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    commands: Array<{
      approved: boolean;
      reason: string;
      attempt: number;
    }>;
    afterSale: {
      afterSaleNo: string;
      status: string;
      reviewReason: string | null;
      version: number;
    };
  };
}

async function resetAdminReview(
  request: APIRequestContext,
  mode: "normal" | "reply-commit-lost" | "moderation-commit-lost",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/admin-review/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    reportId: string;
    reviewId: string;
    productId: string;
  };
}

async function adminReviewDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/admin-review/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    replyCommands: Array<{
      commandId: string;
      reviewId: string;
      content: string;
      attempt: number;
    }>;
    moderationCommands: Array<{
      commandId: string;
      reportId: string;
      resolution: "UPHELD" | "REJECTED";
      reason: string;
      attempt: number;
    }>;
    reports: Array<{
      id: string;
      status: "OPEN" | "RESOLVED";
      resolution: "UPHELD" | "REJECTED" | null;
    }>;
    reviews: Array<{
      id: string;
      status: "PUBLISHED" | "HIDDEN";
      reply: null | { id: string; content: string };
    }>;
    summary: {
      productId: string;
      reviewCount: number;
      averageRating: number;
    };
  };
}

async function resetAdminChat(
  request: APIRequestContext,
  mode: "normal" | "recovery-chain",
) {
  const response = await request.post(
    "http://127.0.0.1:18090/__test__/fixtures/admin-chat/reset",
    { data: { mode } },
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    conversationId: string;
  };
}

async function adminChatDiagnostics(request: APIRequestContext) {
  const response = await request.get(
    "http://127.0.0.1:18090/__test__/fixtures/admin-chat/diagnostics",
  );
  expect(response.ok()).toBe(true);
  return (await response.json()).data as {
    mode: string;
    claimCommands: Array<{
      conversationId: string;
      operatorId: string;
      attempt: number;
    }>;
    sendCommands: Array<{
      conversationId: string;
      operatorId: string;
      clientMessageId: string;
      content: string;
      attempts: number;
    }>;
    closeCommands: Array<{
      conversationId: string;
      operatorId: string;
      attempt: number;
    }>;
    preClaimMessageReads: number;
    conversations: Array<{
      id: string;
      assignedAgentId: string | null;
      status: string;
    }>;
    messages: Array<{
      conversationId: string;
      messages: Array<{
        id: string;
        senderId: string;
        clientMessageId: string;
        content: string;
        status: string;
      }>;
    }>;
  };
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
        // REST/MySQL is authoritative; this socket proves only browser lifecycle.
      }

      close(code = 1000, reason = ""): void {
        this.readyState = ControlledWebSocket.CLOSED;
        this.onclose?.(new CloseEvent("close", {
          code,
          reason,
          wasClean: true,
        }));
      }

      addEventListener(): void {
        // The application uses direct onopen/onmessage/onclose handlers.
      }

      removeEventListener(): void {
        // The application uses direct onopen/onmessage/onclose handlers.
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

async function serveCatalogImages(page: Page) {
  await page.route("**/images/catalog/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const filename = path.basename(pathname);
    const assetPath = path.join(
      process.cwd(),
      "storefront-web",
      "public",
      "images",
      "catalog",
      filename,
    );
    await route.fulfill({
      path: assetPath,
      contentType: "image/png",
    });
  });
}

test.describe.configure({ mode: "serial" });

test("V6.4 Operations home preserves string identities and known projection facts across a failed refresh", async ({
  page,
}) => {
  // Keep the rolling 30-day query deterministic without changing production date logic.
  await page.clock.setFixedTime(new Date("2026-08-15T12:00:00Z"));
  const diagnostics = observeDiagnostics(page);
  const analyticsRequests: Array<{
    authorization: string | undefined;
    from: string | null;
    to: string | null;
    productLimit: string | null;
  }> = [];
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (
      browserRequest.method() === "GET"
      && url.pathname === "/api/v1/analytics/overview"
    ) {
      analyticsRequests.push({
        authorization: browserRequest.headers().authorization,
        from: url.searchParams.get("from"),
        to: url.searchParams.get("to"),
        productLimit: url.searchParams.get("productLimit"),
      });
    }
  });

  await loginAdminAt(page, "/");
  await expect(
    page.getByRole("heading", { name: "工作区", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText("8 个入口")).toBeVisible();
  await expect(
    page.getByText("2088000000000000101", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("青荷帆布通勤袋")).toBeVisible();
  await expect(page.getByText("投影生成", { exact: true })).toBeVisible();

  expect(analyticsRequests).toHaveLength(1);
  expect(analyticsRequests[0]).toMatchObject({
    authorization: "Bearer browser-admin-access-token",
    productLimit: "8",
  });
  expect(analyticsRequests[0]?.from).toMatch(/^\d{4}-\d{2}-\d{2}$/u);
  expect(analyticsRequests[0]?.to).toMatch(/^\d{4}-\d{2}-\d{2}$/u);
  expect(String(analyticsRequests[0]?.from) <= String(analyticsRequests[0]?.to))
    .toBe(true);

  await page.route("**/api/v1/analytics/overview?*", async (route) => {
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SERVICE_UNAVAILABLE",
        message: "analytics projection temporarily unavailable",
        data: null,
        timestamp: "2026-08-03T08:10:00Z",
      }),
    });
  });
  await page.getByRole("button", { name: "读取运营投影" }).click();
  await expect(
    page.locator(".pj-status-notice--warning"),
  ).toContainText("本次刷新未确认");
  await expect(
    page.getByText("2088000000000000101", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("青荷帆布通勤袋")).toBeVisible();

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Governance keeps lost Payment responses unknown until audit authority settles them", async ({
  page,
  request,
}) => {
  await resetGovernance(request, "audit-confirmed");
  const diagnostics = observeDiagnostics(page);
  const postedCommands: Array<{
    path: string;
    key: string | undefined;
    reason: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && (
        path.endsWith("/retry-dispatch")
        || path.endsWith("/exception-refunds")
      )
    ) {
      postedCommands.push({
        path,
        key: browserRequest.headers()["idempotency-key"],
        reason: String(
          (browserRequest.postDataJSON() as { reason?: unknown }).reason ?? "",
        ),
      });
    }
  });

  await loginAdmin(page);
  await expect(
    page.getByRole("heading", { name: "补偿与对账", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText("0 条当前记录")).toBeVisible();

  const refundPanel = page.locator("article").filter({
    has: page.getByRole("heading", { name: "退款渠道重派" }),
  });
  await refundPanel.getByLabel("退款号").fill(refundNo);
  await refundPanel.getByLabel("补偿原因")
    .fill("浏览器验证：响应丢失后通过审计收敛退款重派");
  const refundCommandId = await refundPanel.getByLabel("命令 ID").inputValue();
  await refundPanel.getByRole("button", { name: "授权重新派发" }).click();

  const refundUnknown = refundPanel.locator(".pj-status-notice--unknown");
  await expect(refundUnknown).toContainText("命令结果未知");
  await expect(refundPanel.locator(".pj-status-notice--success")).toHaveCount(0);
  await expect(refundPanel.getByLabel("退款号")).toHaveAttribute("readonly", "");
  await expect(refundPanel.getByLabel("补偿原因")).toHaveAttribute("readonly", "");

  await refundPanel.getByRole("button", { name: "读取权威审计" }).click();
  await expect(refundUnknown).toHaveCount(0);
  await expect(refundPanel.locator(".pj-status-notice--success"))
    .toContainText("ACCEPTED");
  await expect(
    refundPanel.getByRole("cell", { name: "NEEDS_ATTENTION → PENDING" }),
  )
    .toBeVisible();

  await page.getByRole("tab", { name: "异常支付退款" }).click();
  const exceptionPanel = page.locator("article").filter({
    has: page.getByRole("heading", { name: "全额原路退款" }),
  });
  await exceptionPanel.getByLabel("支付号").fill(paymentNo);
  await exceptionPanel.getByLabel("授权原因")
    .fill("浏览器验证：异常支付退款只由追加式审计确认");
  const exceptionCommandId =
    await exceptionPanel.getByLabel("命令 ID").inputValue();
  await exceptionPanel.getByRole("button", { name: "授权创建退款" }).click();

  const exceptionUnknown = exceptionPanel.locator(".pj-status-notice--unknown");
  await expect(exceptionUnknown).toContainText("命令结果未知");
  await expect(exceptionPanel.locator(".pj-status-notice--success"))
    .toHaveCount(0);
  await exceptionPanel.getByRole("button", { name: "读取权威审计" }).click();
  await expect(exceptionUnknown).toHaveCount(0);
  await expect(exceptionPanel.locator(".pj-status-notice--success"))
    .toContainText("RF-DEMO-EXCEPTION");

  expect(postedCommands).toEqual([
    {
      path: `/api/v1/payment/admin/refunds/${refundNo}/retry-dispatch`,
      key: refundCommandId,
      reason: "浏览器验证：响应丢失后通过审计收敛退款重派",
    },
    {
      path: `/api/v1/payment/admin/payments/${paymentNo}/exception-refunds`,
      key: exceptionCommandId,
      reason: "浏览器验证：异常支付退款只由追加式审计确认",
    },
  ]);
  const authority = await governanceDiagnostics(request);
  expect(authority.refundCommands[0]).toMatchObject({
    commandId: refundCommandId,
    referenceNo: refundNo,
    attempts: 1,
  });
  expect(authority.exceptionCommands[0]).toMatchObject({
    commandId: exceptionCommandId,
    referenceNo: paymentNo,
    attempts: 1,
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Governance retries an unresolved command with the exact original id and reason", async ({
  page,
  request,
}) => {
  await resetGovernance(request, "retry-required");
  const diagnostics = observeDiagnostics(page);
  const postedKeys: Array<string | undefined> = [];
  const postedReasons: string[] = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith("/retry-dispatch")
    ) {
      postedKeys.push(browserRequest.headers()["idempotency-key"]);
      postedReasons.push(String(
        (browserRequest.postDataJSON() as { reason?: unknown }).reason ?? "",
      ));
    }
  });

  await loginAdmin(page);
  const panel = page.locator("article").filter({
    has: page.getByRole("heading", { name: "退款渠道重派" }),
  });
  await panel.getByLabel("退款号").fill(refundNo);
  await panel.getByLabel("补偿原因")
    .fill("浏览器验证：审计未出现时沿用原命令安全重试");
  const commandId = await panel.getByLabel("命令 ID").inputValue();
  await panel.getByRole("button", { name: "授权重新派发" }).click();
  await expect(panel.locator(".pj-status-notice--unknown"))
    .toContainText("命令结果未知");

  await panel.getByRole("button", { name: "读取权威审计" }).click();
  await expect(panel.locator(".pj-status-notice--unknown"))
    .toContainText("尚未记录当前命令");
  await panel.getByRole("button", { name: "使用原 ID 安全重试" }).click();

  await expect(panel.locator(".pj-status-notice--success"))
    .toContainText("Payment 已接受命令");
  expect(postedKeys).toEqual([commandId, commandId]);
  expect(postedReasons).toEqual([
    "浏览器验证：审计未出现时沿用原命令安全重试",
    "浏览器验证：审计未出现时沿用原命令安全重试",
  ]);
  const authority = await governanceDiagnostics(request);
  expect(authority.refundCommands).toEqual([
    expect.objectContaining({
      commandId,
      referenceNo: refundNo,
      reason: "浏览器验证：审计未出现时沿用原命令安全重试",
      attempts: 2,
    }),
  ]);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Fulfillment keeps a lost logistics command unknown and retries the exact event", async ({
  page,
  request,
}) => {
  await resetFulfillment(request, "trace-retry");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{
    eventId: string;
    description: string;
    occurredAt: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith("/traces")
    ) {
      const payload = browserRequest.postDataJSON() as {
        externalEventId: string;
        description: string;
        occurredAt: string;
      };
      posted.push({
        eventId: payload.externalEventId,
        description: payload.description,
        occurredAt: payload.occurredAt,
      });
    }
  });

  await loginAdminAt(page, "/fulfillment");
  await expect(
    page.getByRole("heading", { name: "履约与退货", level: 1 }),
  ).toBeVisible();
  const order = page.locator("article.fulfillment-detail").filter({
    hasText: "ORD2079000000000004002",
  });
  await expect(order).toContainText("SHIPPED");
  await order.getByLabel("地点").fill("杭州分拨中心");
  await order.getByLabel("经度（可选）").fill("120.155100");
  await order.getByLabel("纬度（可选）").fill("30.274100");
  await order.getByLabel("轨迹说明")
    .fill("浏览器验证：响应丢失后原轨迹事件保持不变");
  const eventId = await order.getByLabel("事件 ID").inputValue();
  await order.getByRole("button", { name: "追加物流轨迹" }).click();

  const unknown = page.locator(".fulfillment-command-notice.pj-status-notice--unknown");
  await expect(unknown).toContainText("命令结果未知");
  await expect(unknown).toContainText("业务号、命令身份和原始载荷已保留");
  await expect(order.getByLabel("轨迹说明")).toHaveAttribute("readonly", "");
  await expect(page.locator(".fulfillment-command-notice.pj-status-notice--success"))
    .toHaveCount(0);

  await unknown.getByRole("button", { name: "读取权威事实" }).click();
  await expect(unknown).toContainText("尚未证明原命令已生效");
  await unknown.getByRole("button", { name: "使用原命令重试" }).click();
  await expect(page.locator(".fulfillment-command-notice.pj-status-notice--success"))
    .toContainText("当前状态为 IN_TRANSIT");

  expect(posted).toHaveLength(2);
  expect(posted[0]).toEqual(posted[1]);
  expect(posted[0]?.eventId).toBe(eventId);
  const authority = await fulfillmentDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "trace",
      referenceNo: authority.fulfillment.fulfillmentNo,
      commandKey: eventId,
      attempts: 2,
    }),
  ]);
  expect(authority.fulfillment.status).toBe("IN_TRANSIT");
  expect(authority.fulfillment.traces).toEqual([
    expect.objectContaining({ externalEventId: eventId }),
  ]);

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Fulfillment does not attribute recovered state to an unobservable exception command id", async ({
  page,
  request,
}) => {
  await resetFulfillment(request, "resolve-retry");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{ commandId: string | undefined; reason: string }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith("/exception/resolve")
    ) {
      posted.push({
        commandId: browserRequest.headers()["idempotency-key"],
        reason: String(
          (browserRequest.postDataJSON() as { reason?: unknown }).reason ?? "",
        ),
      });
    }
  });

  await loginAdminAt(page, "/fulfillment");
  const order = page.locator("article.fulfillment-detail").filter({
    hasText: "ORD2079000000000004002",
  });
  await expect(order).toContainText("EXCEPTION");
  await order.getByLabel("管理员复核说明")
    .fill("浏览器验证：核对包裹后恢复到异常前状态");
  const commandId = await order.getByLabel("恢复命令 ID").inputValue();
  await order.getByRole("button", { name: "恢复到异常前状态" }).click();

  const unknown = page.locator(".fulfillment-command-notice.pj-status-notice--unknown");
  await expect(unknown).toContainText("命令结果未知");
  await unknown.getByRole("button", { name: "读取权威事实" }).click();
  await expect(unknown).toContainText("不公开异常恢复命令 ID");
  await expect(unknown).toContainText("必须沿用原 ID 重试确认");
  await expect(page.locator(".fulfillment-command-notice.pj-status-notice--success"))
    .toHaveCount(0);

  await unknown.getByRole("button", { name: "使用原命令重试" }).click();
  await expect(page.locator(".fulfillment-command-notice.pj-status-notice--success"))
    .toContainText("当前状态为 PICKING");
  expect(posted).toEqual([
    {
      commandId,
      reason: "浏览器验证：核对包裹后恢复到异常前状态",
    },
    {
      commandId,
      reason: "浏览器验证：核对包裹后恢复到异常前状态",
    },
  ]);
  const authority = await fulfillmentDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "resolve",
      commandKey: commandId,
      reason: "浏览器验证：核对包裹后恢复到异常前状态",
      attempts: 2,
    }),
  ]);
  expect(authority.fulfillment.status).toBe("PICKING");
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Inventory keeps a committed lost adjustment unknown until the original movement is replayed", async ({
  page,
  request,
}) => {
  const fixture = await resetInventory(request, "adjustment-retry");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{
    movementNo: string;
    warehouseId: string;
    skuId: string;
    quantityDelta: number;
    reason: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path === "/api/v1/inventory/admin/stocks/adjustments"
    ) {
      posted.push(browserRequest.postDataJSON() as {
        movementNo: string;
        warehouseId: string;
        skuId: string;
        quantityDelta: number;
        reason: string;
      });
    }
  });

  await loginAdminAt(page, "/inventory");
  await expect(
    page.getByRole("heading", { name: "仓库与库存", level: 1 }),
  ).toBeVisible();
  await page.locator("#adjustment-warehouse-id").fill(fixture.warehouseId);
  await page.locator("#adjustment-sku-id").fill(fixture.skuId);
  await page.getByLabel("数量变化").fill("5");
  await page.getByLabel("调整原因")
    .fill("浏览器验证：响应丢失后必须沿用原库存流水");
  const movementNo =
    await page.getByLabel("流水号").inputValue();
  await page.getByRole("button", { name: "提交 Inventory 调整" }).click();

  const unknown = page.locator(
    ".inventory-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("库存命令结果未知");
  await expect(unknown).toContainText(movementNo);
  await expect(page.locator(
    ".inventory-command-notice.pj-status-notice--success",
  )).toHaveCount(0);
  await expect(page.getByLabel("调整原因")).toHaveAttribute("readonly", "");

  await unknown.getByRole("button", { name: "读取权威事实" }).click();
  await expect(unknown).toContainText("不公开 movementNo");
  await expect(unknown).toContainText("不能把当前数量归因于原调整");
  await expect(page.getByText("15", { exact: true })).toBeVisible();
  await unknown.getByRole("button", { name: "使用原流水重试" }).click();
  await expect(page.locator(
    ".inventory-command-notice.pj-status-notice--success",
  )).toContainText(`库存流水 ${movementNo} 已应用`);

  expect(posted).toHaveLength(2);
  expect(posted[0]).toEqual(posted[1]);
  expect(posted[0]).toEqual({
    movementNo,
    warehouseId: fixture.warehouseId,
    skuId: fixture.skuId,
    quantityDelta: 5,
    reason: "浏览器验证：响应丢失后必须沿用原库存流水",
  });
  const authority = await inventoryDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "adjustment",
      commandKey: movementNo,
      attempts: 2,
      applied: true,
    }),
  ]);
  expect(authority.stock).toMatchObject({
    onHand: 15,
    reserved: 2,
    available: 13,
    version: 4,
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Inventory confirms a lost warehouse creation through the unique warehouse fact", async ({
  page,
  request,
}) => {
  await resetInventory(request, "warehouse-authority");
  const diagnostics = observeDiagnostics(page);

  await loginAdminAt(page, "/inventory");
  await page.getByLabel("仓库代码").fill("HZ_WEST");
  await page.getByLabel("仓库名称").fill("杭州西仓");
  await page.getByRole("button", { name: "创建仓库" }).click();

  const unknown = page.locator(
    ".inventory-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("库存命令结果未知");
  await expect(unknown).toContainText("HZ_WEST");
  await expect(
    unknown.getByRole("button", { name: "使用原流水重试" }),
  ).toHaveCount(0);
  await unknown.getByRole("button", { name: "读取权威事实" }).click();

  await expect(page.locator(
    ".inventory-command-notice.pj-status-notice--success",
  )).toContainText("HZ_WEST / 杭州西仓");
  await expect(
    page.getByRole("heading", { name: "杭州西仓", level: 3 }),
  ).toBeVisible();
  const authority = await inventoryDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "warehouse",
      commandKey: "HZ_WEST",
      attempts: 1,
    }),
  ]);
  expect(authority.warehouses).toContainEqual(
    expect.objectContaining({
      code: "HZ_WEST",
      name: "杭州西仓",
    }),
  );
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Marketing retries a lost benefit grant with the exact original identity", async ({
  page,
  request,
}) => {
  const fixture = await resetMarketing(request, "grant-retry");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{
    userId: string;
    ruleCode: string;
    grantKey: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path === "/api/v1/marketing/admin/benefits"
    ) {
      posted.push(browserRequest.postDataJSON() as {
        userId: string;
        ruleCode: string;
        grantKey: string;
      });
    }
  });

  await loginAdminAt(page, "/marketing");
  await expect(
    page.getByRole("heading", { name: "营销权益", level: 1 }),
  ).toBeVisible();
  const grantSection = page.locator("section.marketing-section").filter({
    has: page.getByRole("heading", {
      name: "向顾客发放权益",
      level: 2,
    }),
  });
  await grantSection.getByLabel("顾客 ID").fill(fixture.userId);
  await grantSection.getByLabel("规则代码").fill(fixture.ruleCode);
  const grantKey = await grantSection.getByLabel("发放键").inputValue();
  await grantSection.getByRole("button", { name: "提交权益发放" }).click();

  const unknown = page.locator(
    ".marketing-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("营销命令结果未知");
  await expect(unknown).toContainText("原 grantKey 已保留");
  await expect(page.locator(
    ".marketing-command-notice.pj-status-notice--success",
  )).toHaveCount(0);
  await expect(grantSection.getByLabel("顾客 ID"))
    .toHaveAttribute("readonly", "");
  await unknown.getByRole("button", {
    name: "使用原 grantKey 重试",
  }).click();

  const accepted = page.locator(
    ".marketing-command-notice.pj-status-notice--success",
  );
  await expect(accepted).toContainText("Marketing 已确认权益");
  await expect(grantSection.locator(".marketing-fact"))
    .toContainText("AVAILABLE");
  expect(posted).toEqual([
    {
      userId: fixture.userId,
      ruleCode: fixture.ruleCode,
      grantKey,
    },
    {
      userId: fixture.userId,
      ruleCode: fixture.ruleCode,
      grantKey,
    },
  ]);
  const authority = await marketingDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "grant",
      commandKey: grantKey,
      userId: fixture.userId,
      ruleCode: fixture.ruleCode,
      attempts: 2,
    }),
  ]);
  expect(authority.benefits).toEqual([
    expect.objectContaining({
      userId: fixture.userId,
      ruleCode: fixture.ruleCode,
      status: "AVAILABLE",
    }),
  ]);

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Marketing keeps a committed lost rule unknown without repeating its POST", async ({
  page,
  request,
}) => {
  await resetMarketing(request, "rule-unknown");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<Record<string, unknown>> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path === "/api/v1/marketing/admin/rules"
    ) {
      posted.push(
        browserRequest.postDataJSON() as Record<string, unknown>,
      );
    }
  });

  await loginAdminAt(page, "/marketing");
  const ruleSection = page.locator("section.marketing-section").filter({
    has: page.getByRole("heading", {
      name: "创建优惠规则",
      level: 2,
    }),
  });
  await ruleSection.getByLabel("规则代码").fill("V643-RULE-LOST");
  await ruleSection.getByLabel("规则名称").fill("响应丢失规则");
  await ruleSection.getByLabel("使用门槛").fill("200.00");
  await ruleSection.getByLabel("优惠金额").fill("20.00");
  await ruleSection.getByLabel("叠加顺序").fill("20");
  await ruleSection.getByLabel("开始时间").fill("2026-08-03T08:00");
  await ruleSection.getByLabel("结束时间").fill("2026-09-03T08:00");
  await ruleSection.getByRole("button", { name: "提交规则创建" }).click();

  const unknown = page.locator(
    ".marketing-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("营销命令结果未知");
  await expect(unknown).toContainText("没有管理端规则查询");
  await expect(unknown).toContainText("不能通过重读或重复 POST 归因原命令");
  await expect(
    unknown.getByRole("button", { name: "使用原 grantKey 重试" }),
  ).toHaveCount(0);
  await expect(ruleSection.locator(".marketing-fact")).toHaveCount(0);
  await expect(ruleSection.getByLabel("规则代码"))
    .toHaveAttribute("readonly", "");

  expect(posted).toHaveLength(1);
  expect(posted[0]).toMatchObject({
    ruleCode: "V643-RULE-LOST",
    name: "响应丢失规则",
    thresholdAmount: "200.00",
    discountAmount: "20.00",
    stackOrder: 20,
  });
  const authority = await marketingDiagnostics(request);
  expect(authority.commands).toEqual([
    expect.objectContaining({
      kind: "rule",
      commandKey: "V643-RULE-LOST",
      attempts: 1,
    }),
  ]);
  expect(authority.rules).toContainEqual(
    expect.objectContaining({
      ruleCode: "V643-RULE-LOST",
      name: "响应丢失规则",
    }),
  );
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Catalog keeps the admin view as a public projection with images, filters and pagination facts", async ({
  page,
}) => {
  await serveCatalogImages(page);
  const productRequests: string[] = [];
  const diagnostics = observeDiagnostics(page);
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (
      browserRequest.method() === "GET"
      && url.pathname === "/api/v1/catalog/products"
    ) {
      productRequests.push(url.search);
    }
  });

  await loginAdminAt(page, "/catalog");
  await expect(
    page.getByRole("heading", { name: "商品目录", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText(/2 条 · 读取于/u)).toBeVisible();
  await expect(page.locator(".catalog-list > li")).toHaveCount(2);
  await expect(
    page.locator(".catalog-list").getByAltText("帆布通勤袋 商品图"),
  ).toBeVisible();
  await expect(
    page.locator(".catalog-list").getByAltText("青灰随行本 商品图"),
  ).toBeVisible();
  await expect(
    page.locator(".catalog-pagination")
      .getByText(/显示 1–2 · 第 1 \/ 1 页/u),
  ).toBeVisible();

  await page.getByLabel("分类", { exact: true })
    .selectOption("2079000000000000101");
  await page.getByRole("button", { name: "应用筛选" }).click();
  await expect(page.getByText(/1 条 · 读取于/u)).toBeVisible();
  await expect(page.locator(".catalog-list > li")).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "帆布通勤袋", level: 2 }))
    .toBeVisible();
  expect(productRequests.at(-1)).toBe(
    "?page=1&size=20&categoryId=2079000000000000101",
  );
  expect(productRequests.every((search) =>
    !search.includes("/admin/"))).toBe(true);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Catalog preserves known products when a public projection refresh returns 503", async ({
  page,
}) => {
  await serveCatalogImages(page);
  const diagnostics = observeDiagnostics(page);
  let failNext = false;
  await page.route("**/api/v1/catalog/products?*", async (route) => {
    if (failNext) {
      failNext = false;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          code: "SERVICE_UNAVAILABLE",
          message: "catalog replica unavailable",
          data: null,
          timestamp: "2026-08-03T00:00:00Z",
        }),
      });
      return;
    }
    await route.continue();
  });

  await loginAdminAt(page, "/catalog");
  await expect(page.locator(".catalog-list > li")).toHaveCount(2);
  failNext = true;
  await page.getByRole("button", { name: "重新读取" }).click();
  await expect(
    page.locator(".pj-status-notice--danger")
      .filter({ hasText: "商品投影读取未完成" }),
  ).toBeVisible();
  await expect(page.locator(".catalog-list > li")).toHaveCount(2);
  await expect(page.getByText(/保留上一次已显示的商品事实/u)).toBeVisible();

  await page.getByRole("button", { name: "重新读取" }).click();
  await expect(
    page.locator(".pj-status-notice--danger")
      .filter({ hasText: "商品投影读取未完成" }),
  ).toHaveCount(0);
  await expect(page.locator(".catalog-list > li")).toHaveCount(2);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 After-sale presents one continuous Trade fact and accepts only the matching review response", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminAfterSale(request, "normal");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{ approved: boolean; reason: string }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/${fixture.afterSaleNo}/review`)
    ) {
      posted.push(browserRequest.postDataJSON() as {
        approved: boolean;
        reason: string;
      });
    }
  });

  await loginAdminAt(page, "/after-sales");
  await expect(
    page.getByRole("heading", { name: "售后审核", level: 1 }),
  ).toBeVisible();
  await expect(page.locator(".after-sale-queue__list > li")).toHaveCount(1);
  const detail = page.locator("article.after-sale-detail");
  await expect(detail).toContainText("Trade 管理审核");
  await expect(detail).toContainText("不可变退款快照");
  await expect(detail).toContainText("¥20.00");
  await expect(detail).toContainText("2079000000000000011");

  const statusFilter = page.getByRole("navigation", { name: "售后状态筛选" });
  await statusFilter.getByRole("button", { name: "顾客已撤销" }).click();
  await expect(page.getByText("当前筛选下没有售后单", { exact: true }))
    .toBeVisible();
  await statusFilter.getByRole("button", { name: "全部售后" }).click();
  await expect(page.locator(".after-sale-queue__list > li")).toHaveCount(1);

  const reason = "浏览器验证：核对整单金额与商品行快照后同意退货退款";
  await page.getByLabel("审核原因").fill(reason);
  await page.getByRole("button", { name: "批准退款" }).click();

  await expect(
    page.locator(".after-sale-command-notice.pj-status-notice--success"),
  ).toContainText("当前状态为 WAIT_RETURN");
  await expect(detail).toContainText("等待寄回");
  await expect(detail).toContainText(reason);
  expect(posted).toEqual([{ approved: true, reason }]);

  const authority = await adminAfterSaleDiagnostics(request);
  expect(authority.commands).toEqual([
    { approved: true, reason, attempt: 1 },
  ]);
  expect(authority.afterSale).toMatchObject({
    afterSaleNo: fixture.afterSaleNo,
    status: "WAIT_RETURN",
    reviewReason: reason,
    version: 1,
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 After-sale confirms a committed lost review through matching Trade authority without a second POST", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminAfterSale(request, "commit-lost");
  const diagnostics = observeDiagnostics(page);
  let postCount = 0;
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/${fixture.afterSaleNo}/review`)
    ) {
      postCount += 1;
    }
  });

  await loginAdminAt(page, "/after-sales");
  const reason = "浏览器验证：提交已落库但审核响应丢失";
  await page.getByLabel("审核原因").fill(reason);
  await page.getByRole("button", { name: "批准退款" }).click();

  const unknown = page.locator(
    ".after-sale-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("当前结论尚不能确认");
  await expect(page.getByLabel("审核原因")).toHaveAttribute("readonly", "");
  await expect(
    unknown.getByRole("button", { name: "使用原审核载荷重试" }),
  ).toHaveCount(0);
  await expect(
    page.locator(".after-sale-command-notice.pj-status-notice--success"),
  ).toHaveCount(0);

  await unknown.getByRole("button", {
    name: "读取 Trade 权威事实",
  }).click();
  const accepted = page.locator(
    ".after-sale-command-notice.pj-status-notice--success",
  );
  await expect(accepted).toContainText("相同审核决定与原因");
  await expect(accepted).toContainText("不伪造命令身份");
  expect(postCount).toBe(1);

  const authority = await adminAfterSaleDiagnostics(request);
  expect(authority.commands).toEqual([
    { approved: true, reason, attempt: 1 },
  ]);
  expect(authority.afterSale).toMatchObject({
    status: "WAIT_RETURN",
    reviewReason: reason,
    version: 1,
  });
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 After-sale retries the frozen review payload only after Trade still reports APPLIED", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminAfterSale(request, "retry-required");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{ approved: boolean; reason: string }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/${fixture.afterSaleNo}/review`)
    ) {
      posted.push(browserRequest.postDataJSON() as {
        approved: boolean;
        reason: string;
      });
    }
  });

  await loginAdminAt(page, "/after-sales");
  const reason = "浏览器验证：权威仍待审核后沿用原载荷重试";
  await page.getByLabel("审核原因").fill(reason);
  await page.getByRole("button", { name: "批准退款" }).click();

  const unknown = page.locator(
    ".after-sale-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("必须先读取 Trade 权威事实");
  await unknown.getByRole("button", {
    name: "读取 Trade 权威事实",
  }).click();
  await expect(unknown).toContainText("Trade 当前仍为 APPLIED");
  await expect(page.getByLabel("审核原因")).toHaveValue(reason);
  await expect(page.getByLabel("审核原因")).toHaveAttribute("readonly", "");
  await unknown.getByRole("button", {
    name: "使用原审核载荷重试",
  }).click();

  await expect(
    page.locator(".after-sale-command-notice.pj-status-notice--success"),
  ).toContainText("当前状态为 WAIT_RETURN");
  expect(posted).toEqual([
    { approved: true, reason },
    { approved: true, reason },
  ]);

  const authority = await adminAfterSaleDiagnostics(request);
  expect(authority.commands).toEqual([
    { approved: true, reason, attempt: 1 },
    { approved: true, reason, attempt: 2 },
  ]);
  expect(authority.afterSale).toMatchObject({
    status: "WAIT_RETURN",
    reviewReason: reason,
    version: 1,
  });
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Review replays a committed lost platform reply with the exact command id", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminReview(request, "reply-commit-lost");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{
    commandId: string | undefined;
    content: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/admin/reviews/${fixture.reviewId}/reply`)
    ) {
      posted.push({
        commandId: browserRequest.headers()["idempotency-key"],
        content: String(
          (browserRequest.postDataJSON() as { content?: unknown }).content ?? "",
        ),
      });
    }
  });

  await loginAdminAt(page, "/reviews");
  await expect(
    page.getByRole("heading", { name: "评价治理", level: 1 }),
  ).toBeVisible();
  await expect(page.locator(".review-queue__list > li")).toHaveCount(1);
  const reviewDetail = page.locator("article.review-detail");
  await expect(reviewDetail).toContainText(fixture.reportId);
  await expect(reviewDetail).toContainText(fixture.reviewId);
  await expect(reviewDetail).toContainText(fixture.productId);

  const replyPanel = page.locator("form.review-action").filter({
    has: page.getByRole("heading", {
      name: "补充可核实事实",
      level: 3,
    }),
  });
  const content = "平台已核对不可变订单行、商品规格与履约记录。";
  await replyPanel.getByLabel("平台公开回复").fill(content);
  const commandId = (
    await replyPanel.locator(".review-command-identity code").textContent()
  )?.trim();
  expect(commandId).toMatch(/^review-reply:/u);
  await replyPanel.getByRole("button", { name: "保存平台回复" }).click();

  const unknown = page.locator(
    ".review-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("命令结果未知");
  await expect(unknown).toContainText("只能原样重放");
  await expect(replyPanel.getByLabel("平台公开回复"))
    .toHaveAttribute("readonly", "");
  await expect(
    page.locator(".review-command-notice.pj-status-notice--success"),
  ).toHaveCount(0);

  await unknown.getByRole("button", {
    name: "使用原命令 ID 重试",
  }).click();

  await expect(
    page.locator(".review-command-notice.pj-status-notice--success"),
  ).toContainText(`评价 ${fixture.reviewId} 的平台公开回复`);
  await expect(replyPanel.getByText(content)).toBeVisible();
  expect(posted).toEqual([
    { commandId, content },
    { commandId, content },
  ]);

  const authority = await adminReviewDiagnostics(request);
  expect(authority.replyCommands).toEqual([
    {
      commandId,
      reviewId: fixture.reviewId,
      content,
      attempt: 1,
    },
    {
      commandId,
      reviewId: fixture.reviewId,
      content,
      attempt: 2,
    },
  ]);
  expect(authority.reviews[0]).toMatchObject({
    id: fixture.reviewId,
    status: "PUBLISHED",
    reply: {
      content,
    },
  });
  expect(authority.reports[0]).toMatchObject({
    id: fixture.reportId,
    status: "OPEN",
    resolution: null,
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Review accepts moderation only from the exact audit replay and keeps resolution separate from visibility", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminReview(request, "moderation-commit-lost");
  const diagnostics = observeDiagnostics(page);
  const posted: Array<{
    commandId: string;
    resolution: "UPHELD" | "REJECTED";
    reason: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/reports/${fixture.reportId}/resolve`)
    ) {
      posted.push(browserRequest.postDataJSON() as {
        commandId: string;
        resolution: "UPHELD" | "REJECTED";
        reason: string;
      });
    }
  });

  await loginAdminAt(page, "/reviews");
  const moderationPanel = page.locator("form.review-action").filter({
    has: page.getByRole("heading", {
      name: "记录举报结论",
      level: 3,
    }),
  });
  const reason = "浏览器验证：核对订单快照和商品规格后确认错误信息举报成立。";
  await moderationPanel.getByLabel("审核结论").selectOption("UPHELD");
  await moderationPanel.getByLabel("审核说明").fill(reason);
  const commandId = (
    await moderationPanel.locator(".review-command-identity code").textContent()
  )?.trim() ?? "";
  expect(commandId).toMatch(/^review-moderation:/u);
  await moderationPanel.getByRole("button", { name: "提交审核结论" }).click();

  const unknown = page.locator(
    ".review-command-notice.pj-status-notice--unknown",
  );
  await expect(unknown).toContainText("命令结果未知");
  await expect(unknown).toContainText(commandId);
  await expect(moderationPanel.getByLabel("审核说明"))
    .toHaveAttribute("readonly", "");
  await expect(page.locator(".review-queue__list > li")).toHaveCount(1);
  await expect(
    page.locator(".review-command-notice.pj-status-notice--success"),
  ).toHaveCount(0);

  await unknown.getByRole("button", {
    name: "使用原命令 ID 重试",
  }).click();

  const accepted = page.locator(
    ".review-command-notice.pj-status-notice--success",
  );
  await expect(accepted).toContainText(commandId);
  await expect(accepted).toContainText("PUBLISHED");
  await expect(accepted).toContainText("HIDDEN");
  await expect(accepted).toContainText("评分汇总扣回一次");
  await expect(page.locator(".review-queue__list > li")).toHaveCount(0);
  expect(posted).toEqual([
    {
      commandId,
      resolution: "UPHELD",
      reason,
    },
    {
      commandId,
      resolution: "UPHELD",
      reason,
    },
  ]);

  const authority = await adminReviewDiagnostics(request);
  expect(authority.moderationCommands).toEqual([
    {
      commandId,
      reportId: fixture.reportId,
      resolution: "UPHELD",
      reason,
      attempt: 1,
    },
    {
      commandId,
      reportId: fixture.reportId,
      resolution: "UPHELD",
      reason,
      attempt: 2,
    },
  ]);
  expect(authority.reports[0]).toMatchObject({
    id: fixture.reportId,
    status: "RESOLVED",
    resolution: "UPHELD",
  });
  expect(authority.reviews[0]).toMatchObject({
    id: fixture.reviewId,
    status: "HIDDEN",
  });
  expect(authority.summary).toMatchObject({
    productId: fixture.productId,
    reviewCount: 0,
    averageRating: 0,
  });

  await page.getByRole("navigation", { name: "评价举报状态筛选" })
    .getByRole("button", { name: "已完成审核" })
    .click();
  await expect(page.locator(".review-queue__list > li")).toHaveCount(1);
  await expect(page.getByText("UPHELD", { exact: true })).toBeVisible();
  await expect(page.locator(".review-queue__list > li")).toContainText("审核完成");

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});

test("V6.4 Chat opens private history only after member authority and preserves recovery identities", async ({
  page,
  request,
}) => {
  const fixture = await resetAdminChat(request, "recovery-chain");
  const diagnostics = observeDiagnostics(page);
  await installFakeWebSocket(page);
  const postedMessages: Array<{
    clientMessageId: string;
    content: string;
  }> = [];
  page.on("request", (browserRequest) => {
    const path = new URL(browserRequest.url()).pathname;
    if (
      browserRequest.method() === "POST"
      && path.endsWith(`/conversations/${fixture.conversationId}/messages`)
    ) {
      const payload = browserRequest.postDataJSON() as {
        clientMessageId: string;
        content: string;
      };
      postedMessages.push({
        clientMessageId: payload.clientMessageId,
        content: payload.content,
      });
    }
  });

  await loginAdminAt(page, "/chat");
  await expect(page.getByText("实时可用", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: /帆布通勤袋保养方式/u }).click();
  await expect(page).toHaveURL(
    new RegExp(`/chat/${fixture.conversationId}$`, "u"),
  );
  await expect(page.getByText("请问帆布通勤袋可以水洗吗？"))
    .toHaveCount(0);

  await page.getByRole("button", { name: "认领并读取会话" }).click();
  await expect(page.getByText("请问帆布通勤袋可以水洗吗？"))
    .toBeVisible();
  await expect(
    page.locator(".pj-status-notice--success"),
  ).toContainText("权威成员事实");

  const reply = "浏览器验证：请按商品洗护标签局部清洁，避免长时间浸泡。";
  await page.getByLabel("回复顾客").fill(reply);
  await page.getByRole("button", { name: "发送客服回复" }).click();

  const sendUnknown = page.locator(".pj-status-notice--unknown").filter({
    hasText: "客服回复结果未知",
  });
  await expect(sendUnknown).toContainText("原客户端消息键");
  const clientMessageId = (
    await sendUnknown.locator("code").textContent()
  )?.trim() ?? "";
  expect(clientMessageId).toMatch(/^chat:message:/u);
  await expect(page.getByLabel("回复顾客")).toHaveAttribute("readonly", "");
  await expect(page.getByRole("button", { name: "结束会话" }))
    .toBeDisabled();

  await sendUnknown.getByRole("button", {
    name: "使用原消息键查询并重试",
  }).click();
  await expect(page.getByText(reply)).toBeVisible();
  await expect(sendUnknown).toHaveCount(0);
  expect(postedMessages).toEqual([
    { clientMessageId, content: reply },
    { clientMessageId, content: reply },
  ]);

  await page.getByRole("button", { name: "结束会话" }).click();
  await expect(page.getByText("CLOSED", { exact: true })).toBeVisible();
  await expect(page.getByText("该会话已经关闭，历史消息保留只读。"))
    .toBeVisible();
  await expect(page.getByLabel("回复顾客")).toHaveCount(0);

  const authority = await adminChatDiagnostics(request);
  expect(authority.preClaimMessageReads).toBe(0);
  expect(authority.claimCommands).toEqual([{
    conversationId: fixture.conversationId,
    operatorId: "2079000000000001999",
    attempt: 1,
  }]);
  expect(authority.sendCommands).toEqual([
    expect.objectContaining({
      conversationId: fixture.conversationId,
      operatorId: "2079000000000001999",
      clientMessageId,
      content: reply,
      attempts: 2,
    }),
  ]);
  expect(authority.closeCommands).toEqual([{
    conversationId: fixture.conversationId,
    operatorId: "2079000000000001999",
    attempt: 1,
  }]);
  expect(authority.conversations[0]).toMatchObject({
    id: fixture.conversationId,
    assignedAgentId: "2079000000000001999",
    status: "CLOSED",
  });
  expect(authority.messages[0]?.messages).toEqual([
    expect.objectContaining({
      id: "2079000000000005101",
      senderId: "2079000000000000999",
      status: "STORED",
    }),
    expect.objectContaining({
      senderId: "2079000000000001999",
      clientMessageId,
      content: reply,
      status: "STORED",
    }),
  ]);

  await page.reload();
  await expect(page.getByText(reply)).toBeVisible();
  await expect(page.getByText("CLOSED", { exact: true })).toBeVisible();
  await expect(page.getByLabel("回复顾客")).toHaveCount(0);

  await page.setViewportSize({ width: 1280, height: 900 });
  await expectNoRootOverflow(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoRootOverflow(page);
  await expectNoSeriousAccessibilityViolations(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoRootOverflow(page);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleWarnings).toEqual([]);
  expect(unexpectedFailedResponses(diagnostics.failedResponses)).toEqual([]);
  expect(unexpectedConsoleErrors(diagnostics.consoleErrors)).toEqual([]);
});
