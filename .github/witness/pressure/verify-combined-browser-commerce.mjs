import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { readFile, rename, writeFile } from "node:fs/promises";
import { setTimeout } from "node:timers/promises";

const [directory] = process.argv.slice(2);
assert(directory && process.env.PJ_SOURCE);
const require = createRequire(resolve(process.env.PJ_SOURCE, "frontend/package.json"));
const { chromium, expect } = require("@playwright/test");
const source = "53ecf628741e2df3ab43e5f744d1dbabf4131883";
const path = name => resolve(directory, name + ".json");
const workspace = JSON.parse(await readFile(path("workspace"), "utf8"));
assert.equal(workspace.source, source);
assert(/^http:\/\/127\.0\.0\.1:\d+$/.test(workspace.url), "Owned browser fixture only");

const browser = await chromium.launch();
const paymentPath = "/api/v1/payment/payments";
const orderPath = "/api/v1/trade/orders";
const order = workspace.order;

async function save(name, payload) {
  const destination = path(name);
  await writeFile(destination + ".tmp", JSON.stringify({ source, ...payload }, null, 2) + "\n");
  await rename(destination + ".tmp", destination);
}

async function waitFor(name) {
  const until = Date.now() + 420000;
  while (Date.now() < until) {
    try {
      const state = JSON.parse(await readFile(path(name), "utf8"));
      assert.equal(state.source, source);
      return state;
    } catch (error) {
      if (error.code !== "ENOENT" && !(error instanceof SyntaxError)) throw error;
    }
    await setTimeout(1000);
  }
  throw new Error("Fixture did not publish " + name);
}

async function session(timezoneId) {
  const context = await browser.newContext({ baseURL: workspace.url, timezoneId });
  const page = await context.newPage();
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("price-witness@example.invalid");
  await page.getByLabel("密码").fill("SyntheticTestPass123");
  const response = page.waitForResponse(reply => new URL(reply.url()).pathname === "/api/v1/identity/auth/login");
  await page.getByRole("button", { name: "登录 →" }).click();
  const reply = await response;
  assert.equal(reply.status(), 200);
  await expect(page).toHaveURL(/\/account$/);
  return { context, page, token: (await reply.json()).data.accessToken };
}

async function paymentIntent(customer, key) {
  return customer.page.evaluate(async request => {
    const reply = await fetch(request.path, {
      method: "POST",
      headers: { authorization: "Bearer " + request.token, "Idempotency-Key": request.key,
                 "Content-Type": "application/json" },
      body: JSON.stringify({ orderNo: request.order, channel: "MOCK" }),
    });
    return { status: reply.status, data: (await reply.json()).data };
  }, { path: paymentPath, order, key, token: customer.token });
}

try {
  const first = await session("Asia/Shanghai");
  const second = await session("Asia/Shanghai");
  assert.notEqual(first.token, second.token);
  let lostResponse;
  const processing = Promise.withResolvers();
  await first.page.route("**/api/v1/payment/payments", async route => {
    const response = await route.fetch();
    lostResponse = { status: response.status(), data: (await response.json()).data };
    await route.abort("failed");
    await first.context.setOffline(true);
    processing.resolve();
  });
  const started = performance.now();
  const attempts = await Promise.allSettled([
    paymentIntent(first, "COMPOUND-PAY-ONE"),
    paymentIntent(second, "COMPOUND-PAY-TWO"),
  ]);
  await Promise.race([processing.promise, setTimeout(12000).then(() => { throw new Error("First client did not lose its response"); })]);
  assert.equal(attempts[0].status, "rejected");
  assert.equal(attempts[1].status, "fulfilled");
  const usable = attempts[1].value;
  assert.equal(usable.status, 200);
  assert.equal(lostResponse.status, 200);
  const paymentNo = usable.data.paymentNo;
  assert.equal(lostResponse.data.paymentNo, paymentNo);
  assert.equal(usable.data.status, "PROCESSING");
  const offlineRead = await first.page.evaluate(async url => {
    try { await fetch(url); return false; } catch { return true; }
  }, paymentPath + "/by-order/" + order);
  assert(offlineRead);
  await save("browser-initial-intents", { order, paymentNo, lostResponse: true, offlineRead: true,
                                          intentCalls: 2, burstElapsedMs: performance.now() - started });
  await first.context.close();
  await second.context.close();

  const request = await waitFor("browser-readback-request");
  assert.equal(request.order, order);
  assert.equal(request.paymentNo, paymentNo);
  const later = await session("America/New_York");
  const timezone = await later.page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  assert.equal(timezone, "America/New_York");
  const headers = { authorization: "Bearer " + later.token };
  const old = await later.context.request.get(orderPath + "/" + order, { headers });
  assert.equal(old.status(), 200);
  assert.equal((await old.json()).data.status, "CLOSED");
  const payment = await later.context.request.get(paymentPath + "/by-order/" + order, { headers });
  assert.equal(payment.status(), 200);
  const paid = (await payment.json()).data;
  assert.equal(paid.paymentNo, paymentNo);
  assert.equal(paid.status, "SUCCESS");
  assert.equal(paid.amount, 39.8);
  const newer = await later.context.request.post(orderPath, {
    headers: { ...headers, "Idempotency-Key": "COMPOUND-NEXT-ORDER" },
    data: { addressId: workspace.address, benefitNos: [],
            items: [{ productId: workspace.product, skuId: workspace.sku, quantity: 1 }] },
  });
  assert.equal(newer.status(), 200);
  const next = (await newer.json()).data;
  assert.notEqual(next.orderNo, order);
  assert.equal(next.status, "PENDING_PAYMENT");
  assert.equal(next.totalAmount, 39.8);
  await save("browser-new-order", { firstTimezone: "Asia/Shanghai", timezone, before: order, newOrder: next.orderNo,
                                    oldPayment: paymentNo, newAmount: next.totalAmount });
  await later.context.close();
} catch (error) {
  await save("combination-browser-failure", { error: String(error) });
  process.exitCode = 1;
} finally {
  await browser.close();
}
