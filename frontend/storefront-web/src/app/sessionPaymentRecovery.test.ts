import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useSessionStore } from "../features/customer-session";
import {
  usePaymentsStore,
  type PaymentAccessContext,
} from "../features/order-payment";

const USER_ID = "2079000000000000999";
const ORDER_NO = "ORD2079000000000000001";
const PAYMENT_NO = "PAY2079000000000000002";

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

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function paymentFixture() {
  return {
    paymentNo: PAYMENT_NO,
    orderNo: ORDER_NO,
    channel: "MOCK",
    status: "PROCESSING",
    amount: "398.00",
    channelTransactionNo: null,
    paidAt: null,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:01:00Z",
  };
}

describe("session and payment recovery composition", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps payment authority and idempotency stable across access-token rotation", async () => {
    const tokens = {
      tokenType: "Bearer",
      accessToken: "access-token-expiring",
      expiresIn: 900,
      refreshToken: "refresh-token-one",
    };
    const rotatedTokens = {
      ...tokens,
      accessToken: "access-token-rotated",
      refreshToken: "refresh-token-two",
    };
    const profile = {
      id: USER_ID,
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    const postedKeys: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        return success(rotatedTokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(profile);
      }
      if (path.includes("/payments/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (path === "/api/v1/payment/payments" && init?.method === "POST") {
        postedKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        return authorization === "Bearer access-token-rotated"
          ? success(paymentFixture())
          : failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const authority = session.requestAuthority;
    const access: PaymentAccessContext = {
      authenticated: session.authenticated,
      ownerId: session.profile?.id ?? null,
      accessToken: authority,
    };
    const payments = usePaymentsStore();

    await expect(payments.createForOrder(access, ORDER_NO))
      .resolves.toMatchObject({ paymentNo: PAYMENT_NO });

    expect(postedKeys).toHaveLength(2);
    expect(postedKeys[1]).toBe(postedKeys[0]);
    expect(session.requestAuthority).toBe(authority);
    expect(payments.submissionUnknown).toBe(false);
    expect(payments.currentAccountPendingSubmission).toBeNull();
  });
});
