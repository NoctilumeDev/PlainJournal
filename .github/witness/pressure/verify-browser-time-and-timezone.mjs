import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const [directory] = process.argv.slice(2);
assert(directory && process.env.PJ_SOURCE, "Specify the active owned browser fixture");
const root = resolve(process.env.PJ_SOURCE);
const file = name => resolve(directory, name);
const workspace = JSON.parse(await readFile(file("workspace.json"), "utf8"));
assert(workspace.source === "53ecf628741e2df3ab43e5f744d1dbabf4131883");
const storefront = workspace.urls["storefront-web"];
assert(/^http:\/\/127\.0\.0\.1:\d+$/.test(storefront), "Owned loopback fixture only");
const require = createRequire(resolve(root, "frontend/package.json"));
const { chromium, expect } = require("@playwright/test");
const browser = await chromium.launch();
const view = "/api/v1/trade/orders/";
const paymentPath = "/api/v1/payment/payments";
const output = { source: workspace.source, merchant: "MOCK", status: "INCONCLUSIVE", cases: [] };

async function session(timezoneId, time) {
  const context = await browser.newContext({ baseURL: storefront, timezoneId, viewport: { width: 1300, height: 900 } });
  const page = await context.newPage();
  if (time) await page.clock.setFixedTime(time);
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("price-witness@example.invalid");
  await page.getByLabel("密码").fill("SyntheticTestPass123");
  const login = page.waitForResponse(res => new URL(res.url()).pathname === "/api/v1/identity/auth/login");
  await page.getByRole("button", { name: "登录 →" }).click();
  const logged = await login;
  assert.equal(logged.status(), 200);
  const { accessToken } = (await logged.json()).data;
  await expect(page).toHaveURL(/\/account$/);
  const client = await page.evaluate(() => ({
    currentTime: new Date().toISOString(),
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    offsetMinutes: new Date().getTimezoneOffset(),
  }));
  return { context, page, headers: { authorization: "Bearer " + accessToken }, client };
}

async function readOrder(current, number) {
  const response = await current.context.request.get(view + number, { headers: current.headers });
  assert.equal(response.status(), 200);
  const order = (await response.json()).data;
  assert.equal(order.orderNo, number);
  return { number, price: order.totalAmount, deadline: order.paymentDeadline, status: order.status };
}

async function expectExpired(current) {
  const answer = await current.context.request.post(paymentPath, {
    headers: { ...current.headers, "idempotency-key": "BROWSER-CLOCK-EXPIRED" },
    data: { orderNo: workspace.expiredOrder, channel: "MOCK" },
  });
  const data = await answer.json();
  assert.equal(answer.status(), 409);
  assert.equal(data.code, "INVALID_STATE");
  return { status: answer.status(), code: data.code };
}

try {
  const baseline = await session("Asia/Shanghai");
  const original = await readOrder(baseline, workspace.pendingOrder);
  const expired = await readOrder(baseline, workspace.expiredOrder);
  assert.equal(original.price, 39.8);
  assert.equal(original.status, "PENDING_PAYMENT");
  const expiredBaseline = await expectExpired(baseline);
  await baseline.context.close();

  const farFuture = new Date("2043-08-10T08:00:00.000Z");
  const clockOnly = await session("Asia/Shanghai", farFuture);
  assert.equal(clockOnly.client.currentTime, farFuture.toISOString());
  assert.equal(clockOnly.client.timezone, baseline.client.timezone);
  assert.deepEqual(await readOrder(clockOnly, workspace.pendingOrder), original);
  await clockOnly.page.goto("/orders/" + workspace.pendingOrder);
  await expect(clockOnly.page.getByRole("heading", { name: "订单详情", level: 1 })).toBeVisible();
  const createIntent = clockOnly.page.waitForResponse(
    res => new URL(res.url()).pathname === paymentPath && res.request().method() === "POST",
  );
  await clockOnly.page.getByRole("button", { name: "创建支付单" }).click();
  const intent = await createIntent;
  assert.equal(intent.status(), 200);
  const created = (await intent.json()).data;
  assert.equal(created.amount, original.price);
  assert.equal(created.status, "PROCESSING");
  output.cases.push({ case: "P0-08", baseline: baseline.client, browser: clockOnly.client,
                      before: original, created: { paymentNo: created.paymentNo, amount: created.amount, status: created.status } });
  await clockOnly.context.close();

  const zoneOnly = await session("America/New_York");
  assert.equal(zoneOnly.client.timezone, "America/New_York");
  assert.notEqual(zoneOnly.client.offsetMinutes, baseline.client.offsetMinutes);
  assert(Math.abs(Date.parse(zoneOnly.client.currentTime) - Date.now()) < 30000);
  assert.deepEqual(await readOrder(zoneOnly, workspace.pendingOrder), original);
  assert.deepEqual(await readOrder(zoneOnly, workspace.expiredOrder), expired);
  const result = await expectExpired(zoneOnly);
  const readback = await zoneOnly.context.request.get(paymentPath + "/by-order/" + workspace.pendingOrder,
    { headers: zoneOnly.headers });
  assert.equal(readback.status(), 200);
  const observed = (await readback.json()).data;
  assert.equal(observed.paymentNo, created.paymentNo);
  assert.equal(observed.amount, 39.8);
  assert.equal(observed.status, "PROCESSING");
  output.cases.push({ case: "P0-09", control: baseline.client, browser: zoneOnly.client,
                      expiredBaseline, expiredZone: result, paymentNo: observed.paymentNo });
  await zoneOnly.context.close();
  output.status = "PASS_BROWSER_SCOPED";
} catch (error) {
  output.error = String(error);
  process.exitCode = 1;
} finally {
  await browser.close();
  await writeFile(file("browser-time-and-timezone.json"), JSON.stringify(output, null, 2) + "\n");
  await writeFile(file("browser-complete.signal"), output.status + "\n");
  console.log(JSON.stringify({ source: output.source, status: output.status, cases: output.cases.map(row => row.case) }));
}
