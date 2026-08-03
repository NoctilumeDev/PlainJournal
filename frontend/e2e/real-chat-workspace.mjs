import fs from "node:fs/promises";
import path from "node:path";

import { chromium, expect } from "@playwright/test";

const required = [
  "PJ_CHAT_CUSTOMER_EMAIL",
  "PJ_CHAT_CUSTOMER_PASSWORD",
  "PJ_CHAT_AGENT_EMAIL",
  "PJ_CHAT_AGENT_PASSWORD",
  "PJ_CHAT_RUN_ID",
  "PJ_CHAT_BROWSER_RESULT",
  "PJ_CHAT_SCREENSHOT_DIRECTORY",
];
for (const name of required) {
  if (!process.env[name]) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
}

const storefrontBaseUrl = process.env.PJ_CHAT_STOREFRONT_URL
  ?? "http://127.0.0.1:18200";
const adminBaseUrl = process.env.PJ_CHAT_ADMIN_URL
  ?? "http://127.0.0.1:18201";
const chromeExecutable = process.env.PLAYWRIGHT_CHROME_EXECUTABLE
  ?? path.join(
    process.env.LOCALAPPDATA ?? "",
    "Google",
    "Chrome",
    "Application",
    "chrome.exe",
  );
const resultPath = path.resolve(process.env.PJ_CHAT_BROWSER_RESULT);
const screenshotDirectory = path.resolve(
  process.env.PJ_CHAT_SCREENSHOT_DIRECTORY,
);
const runId = process.env.PJ_CHAT_RUN_ID;
const subject = `M8.6 real browser ${runId}`;
const customerContent = `M8.6 customer response-drop recovery ${runId}`;
const agentContent = `M8.6 agent realtime reply ${runId}`;
const pageErrors = [];
const consoleErrors = [];
const httpErrors = [];
const networkFailures = [];
const expectedNetworkFailures = new Map();
const creationRequestBodies = [];
const devToolsNetwork = [];
const devToolsRequests = new Map();
const devToolsSockets = new Map();
let unrelatedCartShellRequests = 0;

function requestKey(request) {
  return `${request.method()} ${request.url()}`;
}

function redactedUrl(rawUrl) {
  const url = new URL(rawUrl);
  for (const name of ["ticket", "token", "access_token"]) {
    if (url.searchParams.has(name)) {
      url.searchParams.set(name, "<redacted>");
    }
  }
  return url.toString();
}

function headerValue(headers, name) {
  const entry = Object.entries(headers ?? {}).find(
    ([key]) => key.toLowerCase() === name.toLowerCase(),
  );
  return entry?.[1] ?? null;
}

function sanitizeHeaders(headers) {
  const authorization = headerValue(headers, "authorization");
  return {
    authorization: authorization
      ? `${authorization.split(/\s+/, 1)[0]} <redacted>`
      : null,
    contentType: headerValue(headers, "content-type"),
    idempotencyKey: headerValue(headers, "idempotency-key"),
    requestId: headerValue(headers, "x-request-id"),
  };
}

function summarizePostData(postData) {
  if (!postData) {
    return null;
  }
  try {
    const parsed = JSON.parse(postData);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { kind: typeof parsed };
    }
    return {
      keys: Object.keys(parsed).sort(),
      clientConversationId: parsed.clientConversationId ?? null,
      clientMessageId: parsed.clientMessageId ?? null,
    };
  } catch {
    return { kind: "non-json", length: postData.length };
  }
}

async function observeDevTools(context, page, name) {
  const session = await context.newCDPSession(page);
  await session.send("Network.enable");
  session.on("Network.requestWillBeSent", ({ requestId, request, type }) => {
    const url = new URL(request.url);
    if (
      !url.pathname.startsWith("/api/")
      && type !== "WebSocket"
    ) {
      return;
    }
    const record = {
      browser: name,
      requestId,
      method: request.method,
      url: redactedUrl(request.url),
      type,
      requestHeaders: sanitizeHeaders(request.headers),
      requestBody: summarizePostData(request.postData),
      status: null,
      protocol: null,
      responseRequestId: null,
    };
    devToolsRequests.set(requestId, record);
    devToolsNetwork.push(record);
  });
  session.on("Network.responseReceived", ({ requestId, response }) => {
    const record = devToolsRequests.get(requestId);
    if (!record) {
      return;
    }
    record.status = response.status;
    record.protocol = response.protocol;
    record.responseRequestId = headerValue(response.headers, "x-request-id");
  });
  session.on("Network.webSocketCreated", ({ requestId, url }) => {
    const socket = {
      browser: name,
      requestId,
      url: redactedUrl(url),
      requestHeaders: null,
      status: null,
      protocol: null,
      responseRequestId: null,
      sentFrames: 0,
      receivedFrames: 0,
      closed: false,
    };
    devToolsSockets.set(requestId, socket);
  });
  session.on("Network.webSocketWillSendHandshakeRequest", ({
    requestId,
    request,
  }) => {
    const socket = devToolsSockets.get(requestId);
    if (socket) {
      socket.requestHeaders = sanitizeHeaders(request.headers);
    }
  });
  session.on("Network.webSocketHandshakeResponseReceived", ({
    requestId,
    response,
  }) => {
    const socket = devToolsSockets.get(requestId);
    if (socket) {
      socket.status = response.status;
      socket.protocol = response.protocol;
      socket.responseRequestId = headerValue(
        response.headers,
        "x-request-id",
      );
    }
  });
  session.on("Network.webSocketFrameSent", ({ requestId }) => {
    const socket = devToolsSockets.get(requestId);
    if (socket) {
      socket.sentFrames += 1;
    }
  });
  session.on("Network.webSocketFrameReceived", ({ requestId }) => {
    const socket = devToolsSockets.get(requestId);
    if (socket) {
      socket.receivedFrames += 1;
    }
  });
  session.on("Network.webSocketClosed", ({ requestId }) => {
    const socket = devToolsSockets.get(requestId);
    if (socket) {
      socket.closed = true;
    }
  });
  return session;
}

function expectNetworkFailure(request) {
  const key = requestKey(request);
  expectedNetworkFailures.set(
    key,
    (expectedNetworkFailures.get(key) ?? 0) + 1,
  );
}

function consumeExpectedNetworkFailure(request) {
  const key = requestKey(request);
  const remaining = expectedNetworkFailures.get(key) ?? 0;
  if (remaining <= 0) {
    return false;
  }
  if (remaining === 1) {
    expectedNetworkFailures.delete(key);
  } else {
    expectedNetworkFailures.set(key, remaining - 1);
  }
  return true;
}

function observe(page, name) {
  page.on("pageerror", (error) => {
    pageErrors.push(`${name}: ${error.message}`);
  });
  page.on("console", (message) => {
    if (
      message.type() === "error"
      && !message.text().startsWith("Failed to load resource:")
    ) {
      consoleErrors.push(`${name}: ${message.text()}`);
    }
  });
  page.on("response", (response) => {
    if (response.status() >= 400) {
      httpErrors.push(
        `${name}: HTTP ${response.status()} ${response.request().method()} ${response.url()}`,
      );
    }
  });
  page.on("requestfailed", (request) => {
    if (!consumeExpectedNetworkFailure(request)) {
      networkFailures.push(
        `${name}: ${request.failure()?.errorText ?? "request failed"} ${requestKey(request)}`,
      );
    }
  });
}

async function login(page, baseUrl, email, password, staff, destination = null) {
  const entryPath = staff && destination ? destination : "/login";
  await page.goto(`${baseUrl}${entryPath}`);
  await page.getByLabel(staff ? "员工邮箱" : "邮箱").fill(email);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", {
    name: staff ? "登录工作区 →" : "登录 →",
  }).click();
  await expect(page).toHaveURL(staff
    ? new RegExp(
      `${baseUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`
      + `${destination ?? "/"}/?$`,
    )
    : /\/account$/);
}

await fs.mkdir(screenshotDirectory, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: chromeExecutable,
});

let customerContext;
let adminContext;
try {
  customerContext = await browser.newContext();
  const customerPage = await customerContext.newPage();
  observe(customerPage, "customer");
  await observeDevTools(customerContext, customerPage, "customer");
  await customerPage.route("**/api/v1/trade/cart/items", async (route) => {
    if (route.request().method() !== "GET") {
      await route.continue();
      return;
    }
    unrelatedCartShellRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SUCCESS",
        message: "success",
        data: [],
      }),
    });
  });
  await login(
    customerPage,
    storefrontBaseUrl,
    process.env.PJ_CHAT_CUSTOMER_EMAIL,
    process.env.PJ_CHAT_CUSTOMER_PASSWORD,
    false,
  );

  let creationResponseDropped = false;
  await customerPage.route("**/api/v1/chat/conversations", async (route) => {
    if (route.request().method() === "POST") {
      creationRequestBodies.push(route.request().postDataJSON());
    }
    if (route.request().method() === "POST" && !creationResponseDropped) {
      creationResponseDropped = true;
      await route.fetch();
      expectNetworkFailure(route.request());
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await customerPage.goto(`${storefrontBaseUrl}/support`);
  await expect(customerPage.getByText("实时更新可用", { exact: true }))
    .toBeVisible({ timeout: 20_000 });
  await customerPage.getByLabel("这次需要什么帮助？").fill(subject);
  await customerPage.getByRole("button", { name: "建立会话" }).click();
  await expect(customerPage.getByText(
    "会话创建结果尚未确认，原创建键已保留，可安全重试。",
  )).toBeVisible();
  const pendingConversation = await customerPage.evaluate(() => {
    const value = localStorage.getItem(
      "plain-journal:customer-chat-pending-conversation:v1",
    );
    return value ? JSON.parse(value) : null;
  });
  if (!pendingConversation?.clientConversationId) {
    throw new Error("Conversation response-drop did not preserve the original client key.");
  }
  await customerPage.getByRole("button", {
    name: "使用原创建键再次确认",
  }).click();
  try {
    await expect(customerPage).toHaveURL(/\/support\/\d+$/, { timeout: 10_000 });
  } catch (error) {
    process.stderr.write(
      `Conversation creation request count: ${creationRequestBodies.length}; `
        + `client keys: ${JSON.stringify(
          creationRequestBodies.map((body) => body?.clientConversationId ?? null),
        )}\n`,
    );
    throw error;
  }
  const conversationId = new URL(customerPage.url()).pathname.split("/").at(-1);
  if (!conversationId || !/^\d+$/.test(conversationId)) {
    throw new Error(`Invalid conversation ID in URL: ${customerPage.url()}`);
  }
  const pendingConversationAfterRetry = await customerPage.evaluate(() =>
    localStorage.getItem(
      "plain-journal:customer-chat-pending-conversation:v1",
    ));
  if (pendingConversationAfterRetry !== null) {
    throw new Error("Conversation retry did not clear the pending client key.");
  }

  let sendResponseDropped = false;
  await customerPage.route(
    /\/api\/v1\/chat\/conversations\/\d+\/messages(?:\?.*)?$/,
    async (route) => {
      if (route.request().method() === "POST" && !sendResponseDropped) {
        sendResponseDropped = true;
        await route.fetch();
        expectNetworkFailure(route.request());
        await route.abort("failed");
        return;
      }
      await route.continue();
    },
  );
  await customerPage.getByLabel("回复内容").fill(customerContent);
  await customerPage.getByRole("button", { name: "发送消息" }).click();
  await expect(customerPage.getByText(customerContent)).toBeVisible({
    timeout: 20_000,
  });
  const pendingSendAfterRecovery = await customerPage.evaluate(() =>
    localStorage.getItem("plain-journal:customer-chat-pending-send:v1"));
  if (pendingSendAfterRecovery !== null) {
    throw new Error("Message query recovery did not clear the pending message key.");
  }
  await expect(customerPage.getByRole("button", {
    name: "使用原消息键查询并重试",
  })).toHaveCount(0);

  adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  observe(adminPage, "admin");
  await observeDevTools(adminContext, adminPage, "admin");
  await login(
    adminPage,
    adminBaseUrl,
    process.env.PJ_CHAT_AGENT_EMAIL,
    process.env.PJ_CHAT_AGENT_PASSWORD,
    true,
    "/chat",
  );
  await expect(adminPage.getByText("实时可用", { exact: true }))
    .toBeVisible({ timeout: 20_000 });
  await adminPage.getByRole("link", { name: new RegExp(subject) }).click();
  await expect(adminPage.getByText(customerContent)).toHaveCount(0);
  await adminPage.getByRole("button", { name: "认领并读取会话" }).click();
  await expect(adminPage.getByText(customerContent)).toBeVisible({
    timeout: 20_000,
  });
  await adminPage.getByLabel("回复顾客").fill(agentContent);
  await adminPage.getByRole("button", { name: "发送客服回复" }).click();
  await expect(adminPage.getByText(agentContent)).toBeVisible();

  await expect(customerPage.getByText(agentContent)).toBeVisible({
    timeout: 30_000,
  });
  await expect(customerPage.getByText("客服已接入", { exact: true }).first())
    .toBeVisible({ timeout: 10_000 });
  await adminPage.screenshot({
    path: path.join(screenshotDirectory, "admin-chat-workspace.png"),
    fullPage: true,
  });
  await customerPage.screenshot({
    path: path.join(screenshotDirectory, "customer-chat-workspace.png"),
    fullPage: true,
  });

  await customerPage.reload();
  await expect(customerPage.getByText("实时更新可用", { exact: true }))
    .toBeVisible({ timeout: 20_000 });
  await expect(customerPage.getByText(customerContent)).toBeVisible();
  await expect(customerPage.getByText(agentContent)).toBeVisible();
  await expect(customerPage.getByRole("button", {
    name: /上传|附件|下载/,
  })).toHaveCount(0);
  await expect(adminPage.getByRole("button", {
    name: /上传|附件|下载/,
  })).toHaveCount(0);

  if (!creationResponseDropped || !sendResponseDropped) {
    throw new Error("The browser response-drop probes did not execute.");
  }
  if (pageErrors.length > 0) {
    throw new Error(`Browser page errors: ${pageErrors.join(" | ")}`);
  }
  if (consoleErrors.length > 0) {
    throw new Error(`Browser console errors: ${consoleErrors.join(" | ")}`);
  }
  if (httpErrors.length > 0) {
    throw new Error(`Browser HTTP errors: ${httpErrors.join(" | ")}`);
  }
  if (networkFailures.length > 0) {
    throw new Error(`Unexpected browser network failures: ${networkFailures.join(" | ")}`);
  }
  if (expectedNetworkFailures.size > 0) {
    throw new Error("The expected response-drop requests did not fail at browser level.");
  }
  const webSocketUpgrades = [...devToolsSockets.values()].filter(
    (socket) => socket.status === 101,
  );
  if (webSocketUpgrades.length < 2) {
    throw new Error(
      `Expected two browser WebSocket 101 upgrades, observed ${webSocketUpgrades.length}.`,
    );
  }
  const authorizedChatRequests = devToolsNetwork.filter(
    (request) => new URL(request.url).pathname.startsWith("/api/v1/chat/")
      && request.requestHeaders.authorization === "Bearer <redacted>",
  );
  if (authorizedChatRequests.length === 0) {
    throw new Error("DevTools capture did not observe an authorized Chat request.");
  }
  const devToolsEvidence = {
    requests: devToolsNetwork,
    webSockets: [...devToolsSockets.values()],
  };
  const serializedDevToolsEvidence = JSON.stringify(devToolsEvidence);
  for (const secret of [
    process.env.PJ_CHAT_CUSTOMER_PASSWORD,
    process.env.PJ_CHAT_AGENT_PASSWORD,
    customerContent,
    agentContent,
  ]) {
    if (serializedDevToolsEvidence.includes(secret)) {
      throw new Error("DevTools evidence contains a credential or private message body.");
    }
  }
  if (
    /Bearer\s+(?!<redacted>)[^\s"]+/i.test(serializedDevToolsEvidence)
    || /[?&](ticket|token|access_token)=(?!%3Credacted%3E|<redacted>)/i
      .test(serializedDevToolsEvidence)
  ) {
    throw new Error("DevTools evidence contains an unredacted bearer token or ticket.");
  }
  const creationClientKeys = creationRequestBodies.map(
    (body) => body?.clientConversationId,
  );
  if (
    creationClientKeys.length < 2
    || creationClientKeys.some(
      (clientKey) => clientKey !== pendingConversation.clientConversationId,
    )
  ) {
    throw new Error("Conversation recovery did not reuse one client conversation key.");
  }

  const result = {
    runId,
    conversationId,
    customerRealtimeConnected: true,
    agentRealtimeConnected: true,
    creationResponseDropped,
    creationRequestCount: creationRequestBodies.length,
    creationClientKey: pendingConversation.clientConversationId,
    creationRequestsUsedSingleClientKey: true,
    creationRecoveredWithOriginalKey: true,
    sendResponseDropped,
    sendRecoveredByAuthoritativeQuery: true,
    supportBodyHiddenBeforeClaim: true,
    customerReceivedAgentReplyWithoutRefresh: true,
    customerAssignmentReconciledWithoutRefresh: true,
    historyRecoveredAfterReload: true,
    attachmentControlsExposed: false,
    unrelatedCartShellRequests,
    pageErrors,
    consoleErrors,
    httpErrors,
    networkFailures,
    devTools: {
      authorizedChatRequestCount: authorizedChatRequests.length,
      webSocket101Count: webSocketUpgrades.length,
      evidence: devToolsEvidence,
    },
    screenshots: [
      "customer-chat-workspace.png",
      "admin-chat-workspace.png",
    ],
  };
  await fs.writeFile(resultPath, JSON.stringify(result, null, 2), "utf8");
  process.stdout.write(`${JSON.stringify(result)}\n`);
} finally {
  await adminContext?.close();
  await customerContext?.close();
  await browser.close();
}
