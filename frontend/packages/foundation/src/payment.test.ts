import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createPaymentApi } from "./payment";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("payment api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps the stable payment idempotency key on create and lookup", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({
        paymentNo: "PAY-001",
        orderNo: "ORD-001",
        channel: "MOCK",
        status: "PROCESSING",
        amount: "398.00",
        channelTransactionNo: null,
        paidAt: null,
        createdAt: "2026-07-21T00:00:00Z",
        updatedAt: "2026-07-21T00:00:00Z",
      });
    }));

    const api = createPaymentApi(createApiClient());
    const key = "payment:00000000-0000-0000-0000-000000000001";
    await api.createPayment({ orderNo: "ORD-001", channel: "MOCK" }, key);
    await api.paymentByIdempotencyKey(key);

    const createHeaders = new Headers(requests[0]?.init?.headers);
    expect(createHeaders.get("Idempotency-Key")).toBe(key);
    expect(requests[1]?.url).toContain(
      "/api/v1/payment/payments/by-idempotency-key/payment%3A00000000-0000-0000-0000-000000000001",
    );
  });

  it("encodes payment and order lookup paths", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      urls.push(String(input));
      return success({});
    }));

    const api = createPaymentApi(createApiClient());
    await api.payment("PAY:2026/07");
    await api.paymentByOrder("ORD:2026/07");

    expect(urls[0]).toContain("/api/v1/payment/payments/PAY%3A2026%2F07");
    expect(urls[1]).toContain("/api/v1/payment/payments/by-order/ORD%3A2026%2F07");
  });

  it("queries customer refunds and preserves the admin compensation command id", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({});
    }));

    const api = createPaymentApi(createApiClient());
    await api.refundByAfterSale("AS:2026/07");
    await api.retryRefundDispatch(
      "REF:2026/07",
      "refund-retry:00000000-0000-0000-0000-000000000001",
      "自动派发持续失败，授权重派",
    );
    await api.refundRetryAudits("REF:2026/07", 25);
    await api.createPaymentExceptionRefund(
      "PAY:2026/07",
      "payment-exception-refund:00000000-0000-0000-0000-000000000001",
      "库存终态无法确认，授权全额原路退款",
    );
    await api.paymentExceptionRefundAudits("PAY:2026/07", 20);

    expect(requests[0]?.url).toContain("/refunds/by-after-sale/AS%3A2026%2F07");
    expect(requests[1]?.url).toContain("/admin/refunds/REF%3A2026%2F07/retry-dispatch");
    expect(new Headers(requests[1]?.init?.headers).get("Idempotency-Key")).toContain(
      "refund-retry:",
    );
    expect(requests[2]?.url).toContain(
      "/admin/refunds/REF%3A2026%2F07/retry-dispatch/audits?limit=25",
    );
    expect(requests[3]?.url).toContain(
      "/admin/payments/PAY%3A2026%2F07/exception-refunds",
    );
    expect(new Headers(requests[3]?.init?.headers).get("Idempotency-Key")).toContain(
      "payment-exception-refund:",
    );
    expect(requests[4]?.url).toContain(
      "/admin/payments/PAY%3A2026%2F07/exception-refunds/audits?limit=20",
    );
  });
});
