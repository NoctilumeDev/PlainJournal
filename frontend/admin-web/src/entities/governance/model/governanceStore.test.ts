import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  PaymentExceptionRefundAudit,
  Refund,
  RefundDispatchRetryAudit,
} from "@plain-journal/foundation";

import {
  useGovernanceStore,
  type GovernanceAccessContext,
} from "./governanceStore";

const OPERATOR_ID = "2084000000000000001";
const ACCESS: GovernanceAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "admin-token",
};
const REFUND_NO = "RF2084000000000000002";
const PAYMENT_NO = "PAY2084000000000000003";
const REFUND_STORAGE =
  `plain-journal:admin-governance:refund-retry:v1:${OPERATOR_ID}`;
const EXCEPTION_STORAGE =
  `plain-journal:admin-governance:payment-exception-refund:v1:${OPERATOR_ID}`;

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
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
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function refundFixture(
  refundNo = REFUND_NO,
  paymentNo = PAYMENT_NO,
): Refund {
  return {
    refundNo,
    afterSaleNo: "AS2084000000000000004",
    orderNo: "ORD2084000000000000005",
    paymentNo,
    userId: "2084000000000000006",
    channel: "MOCK",
    status: "PROCESSING",
    amount: "398.00",
    channelRefundNo: null,
    requestStatus: "PENDING",
    requestAttempts: 0,
    nextRequestAt: "2026-08-03T00:00:00Z",
    requestSentAt: null,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:00:00Z",
    refundedAt: null,
  };
}

function refundAudit(
  commandId: string,
  reason: string,
  outcome: "ACCEPTED" | "REJECTED",
): RefundDispatchRetryAudit {
  return {
    commandId,
    refundNo: REFUND_NO,
    operatorId: OPERATOR_ID,
    reason,
    outcome,
    errorCode: outcome === "REJECTED" ? "REFUND_RETRY_NOT_ALLOWED" : null,
    beforeRefundStatus: "PROCESSING",
    beforeRequestStatus: "NEEDS_ATTENTION",
    beforeRequestAttempts: 5,
    beforeLastError: "channel unavailable",
    afterRefundStatus: "PROCESSING",
    afterRequestStatus: outcome === "ACCEPTED" ? "PENDING" : "NEEDS_ATTENTION",
    afterRequestAttempts: outcome === "ACCEPTED" ? 0 : 5,
    createdAt: "2026-08-03T00:00:00Z",
  };
}

function exceptionAudit(
  commandId: string,
  reason: string,
  outcome: "ACCEPTED" | "REJECTED",
): PaymentExceptionRefundAudit {
  return {
    commandId,
    paymentNo: PAYMENT_NO,
    orderNo: "ORD2084000000000000005",
    refundNo: outcome === "ACCEPTED" ? REFUND_NO : null,
    operatorId: OPERATOR_ID,
    reason,
    outcome,
    errorCode: outcome === "REJECTED"
      ? "PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED"
      : null,
    createdAt: "2026-08-03T00:00:00Z",
  };
}

describe("admin governance entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps the refund command unknown and reuses the original id after a 503", async () => {
    const posted: Array<{ key: string | null; reason: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      posted.push({
        key: new Headers(init?.headers).get("Idempotency-Key"),
        reason: JSON.parse(String(init?.body)).reason as string,
      });
      return failure(503, "SERVICE_UNAVAILABLE", "response lost");
    }));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.refundRetry.referenceNo = REFUND_NO;
    store.refundRetry.reason = "退款渠道持续失败，授权重新派发";
    const commandId = store.refundRetry.commandId;

    await store.submitRefundRetry(ACCESS);
    await store.submitRefundRetry(ACCESS);

    expect(store.refundRetry.phase).toBe("unknown");
    expect(store.refundRetry.commandId).toBe(commandId);
    expect(store.refundRetry.reason).toBe("退款渠道持续失败，授权重新派发");
    expect(posted).toEqual([
      { key: commandId, reason: "退款渠道持续失败，授权重新派发" },
      { key: commandId, reason: "退款渠道持续失败，授权重新派发" },
    ]);
    expect(localStorage.getItem(REFUND_STORAGE)).toContain(commandId);
  });

  it("restores an unresolved command after the store is recreated", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const first = useGovernanceStore();
    first.synchronizeAccess(ACCESS);
    first.refundRetry.referenceNo = REFUND_NO;
    first.refundRetry.reason = "保留本地命令";
    const commandId = first.refundRetry.commandId;
    await first.submitRefundRetry(ACCESS);

    setActivePinia(createPinia());
    const restored = useGovernanceStore();
    restored.synchronizeAccess(ACCESS);

    expect(restored.refundRetry.phase).toBe("unknown");
    expect(restored.refundRetry.referenceNo).toBe(REFUND_NO);
    expect(restored.refundRetry.commandId).toBe(commandId);
    expect(restored.refundRetry.reason).toBe("保留本地命令");
  });

  it("settles an unknown refund command only after the matching audit is ACCEPTED", async () => {
    let commandId = "";
    const reason = "通过审计确认退款重派";
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      if (init?.method === "POST") {
        commandId = new Headers(init.headers).get("Idempotency-Key") ?? "";
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      expect(String(input)).toContain("/retry-dispatch/audits");
      return success([refundAudit(commandId, reason, "ACCEPTED")]);
    }));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.refundRetry.referenceNo = REFUND_NO;
    store.refundRetry.reason = reason;
    await store.submitRefundRetry(ACCESS);

    expect(store.refundRetry.phase).toBe("unknown");
    await store.loadRefundRetryAudits(ACCESS);

    expect(store.refundRetry.phase).toBe("accepted");
    expect(store.refundRetry.message).toContain("ACCEPTED");
    expect(localStorage.getItem(REFUND_STORAGE)).toBeNull();
  });

  it("does not present a REJECTED exception-refund audit as success", async () => {
    let commandId = "";
    const reason = "授权处理异常支付";
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      if (init?.method === "POST") {
        commandId = new Headers(init.headers).get("Idempotency-Key") ?? "";
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success([exceptionAudit(commandId, reason, "REJECTED")]);
    }));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.paymentExceptionRefund.referenceNo = PAYMENT_NO;
    store.paymentExceptionRefund.reason = reason;
    await store.submitPaymentExceptionRefund(ACCESS);
    await store.loadPaymentExceptionAudits(ACCESS);

    expect(store.paymentExceptionRefund.phase).toBe("rejected");
    expect(store.paymentExceptionRefund.message).toContain("REJECTED");
    expect(store.paymentExceptionRefund.message).not.toContain("已创建退款");
    expect(localStorage.getItem(EXCEPTION_STORAGE)).toBeNull();
  });

  it("keeps the command unknown when the authoritative audit cannot be read", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "still unavailable")));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.paymentExceptionRefund.referenceNo = PAYMENT_NO;
    store.paymentExceptionRefund.reason = "审计不可用时继续未知";
    await store.submitPaymentExceptionRefund(ACCESS);
    await store.loadPaymentExceptionAudits(ACCESS);

    expect(store.paymentExceptionRefund.phase).toBe("unknown");
    expect(store.paymentExceptionRefund.auditError).toContain("still unavailable");
    expect(localStorage.getItem(EXCEPTION_STORAGE)).not.toBeNull();
  });

  it("treats an explicit 409 as rejection instead of an unknown result", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(409, "REFUND_RETRY_NOT_ALLOWED", "manual retry is not allowed")));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.refundRetry.referenceNo = REFUND_NO;
    store.refundRetry.reason = "当前状态不允许重派";
    await store.submitRefundRetry(ACCESS);

    expect(store.refundRetry.phase).toBe("rejected");
    expect(store.refundRetry.message).toContain("manual retry is not allowed");
    expect(localStorage.getItem(REFUND_STORAGE)).toBeNull();
  });

  it("keeps a mismatched successful response unknown instead of accepting it", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success(refundFixture("RF-WRONG", PAYMENT_NO))));

    const store = useGovernanceStore();
    store.synchronizeAccess(ACCESS);
    store.refundRetry.referenceNo = REFUND_NO;
    store.refundRetry.reason = "拒绝错误归属的退款事实";
    await store.submitRefundRetry(ACCESS);

    expect(store.refundRetry.phase).toBe("unknown");
    expect(store.refundRetry.message).toContain("退款事实与本次补偿命令不一致");
    expect(localStorage.getItem(REFUND_STORAGE)).not.toBeNull();
  });
});
