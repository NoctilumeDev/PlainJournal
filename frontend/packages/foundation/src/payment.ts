import type { ApiClient } from "./api";

export interface Payment {
  paymentNo: string;
  orderNo: string;
  channel: string;
  status: string;
  amount: string | number;
  channelTransactionNo: string | null;
  paidAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaymentInput {
  orderNo: string;
  channel: string;
}

export interface Refund {
  refundNo: string;
  afterSaleNo: string;
  orderNo: string;
  paymentNo: string;
  userId: string;
  channel: string;
  status: string;
  amount: string | number;
  channelRefundNo: string | null;
  requestStatus: string;
  requestAttempts: number;
  nextRequestAt: string | null;
  requestSentAt: string | null;
  createdAt: string;
  updatedAt: string;
  refundedAt: string | null;
}

export interface RefundDispatchRetryAudit {
  commandId: string;
  refundNo: string;
  operatorId: string;
  reason: string;
  outcome: string;
  errorCode: string | null;
  beforeRefundStatus: string;
  beforeRequestStatus: string;
  beforeRequestAttempts: number;
  beforeLastError: string | null;
  afterRefundStatus: string;
  afterRequestStatus: string;
  afterRequestAttempts: number;
  createdAt: string;
}

export interface PaymentExceptionRefundAudit {
  commandId: string;
  paymentNo: string;
  orderNo: string | null;
  refundNo: string | null;
  operatorId: string;
  reason: string;
  outcome: string;
  errorCode: string | null;
  createdAt: string;
}

export interface PaymentApi {
  createPayment(input: CreatePaymentInput, idempotencyKey: string): Promise<Payment>;
  payment(paymentNo: string): Promise<Payment>;
  paymentByIdempotencyKey(idempotencyKey: string): Promise<Payment>;
  paymentByOrder(orderNo: string): Promise<Payment>;
  refund(refundNo: string): Promise<Refund>;
  refundByAfterSale(afterSaleNo: string): Promise<Refund>;
  retryRefundDispatch(
    refundNo: string,
    commandId: string,
    reason: string,
  ): Promise<Refund>;
  refundRetryAudits(refundNo: string, limit?: number): Promise<RefundDispatchRetryAudit[]>;
  createPaymentExceptionRefund(
    paymentNo: string,
    commandId: string,
    reason: string,
  ): Promise<Refund>;
  paymentExceptionRefundAudits(
    paymentNo: string,
    limit?: number,
  ): Promise<PaymentExceptionRefundAudit[]>;
}

export function createPaymentApi(client: ApiClient): PaymentApi {
  return {
    createPayment(input, idempotencyKey) {
      return client.request<Payment>("/api/v1/payment/payments", {
        method: "POST",
        timeoutMs: 15000,
        headers: {
          "Idempotency-Key": idempotencyKey,
        },
        body: JSON.stringify(input),
      });
    },
    payment(paymentNo) {
      return client.request<Payment>(
        `/api/v1/payment/payments/${encodeURIComponent(paymentNo)}`,
      );
    },
    paymentByIdempotencyKey(idempotencyKey) {
      return client.request<Payment>(
        `/api/v1/payment/payments/by-idempotency-key/${encodeURIComponent(idempotencyKey)}`,
      );
    },
    paymentByOrder(orderNo) {
      return client.request<Payment>(
        `/api/v1/payment/payments/by-order/${encodeURIComponent(orderNo)}`,
      );
    },
    refund(refundNo) {
      return client.request<Refund>(
        `/api/v1/payment/refunds/${encodeURIComponent(refundNo)}`,
      );
    },
    refundByAfterSale(afterSaleNo) {
      return client.request<Refund>(
        `/api/v1/payment/refunds/by-after-sale/${encodeURIComponent(afterSaleNo)}`,
      );
    },
    retryRefundDispatch(refundNo, commandId, reason) {
      return client.request<Refund>(
        `/api/v1/payment/admin/refunds/${encodeURIComponent(refundNo)}/retry-dispatch`,
        {
          method: "POST",
          headers: {
            "Idempotency-Key": commandId,
          },
          body: JSON.stringify({ reason }),
        },
      );
    },
    refundRetryAudits(refundNo, limit = 50) {
      return client.request<RefundDispatchRetryAudit[]>(
        `/api/v1/payment/admin/refunds/${encodeURIComponent(refundNo)}/retry-dispatch/audits`
        + `?limit=${encodeURIComponent(String(limit))}`,
      );
    },
    createPaymentExceptionRefund(paymentNo, commandId, reason) {
      return client.request<Refund>(
        `/api/v1/payment/admin/payments/${encodeURIComponent(paymentNo)}/exception-refunds`,
        {
          method: "POST",
          headers: {
            "Idempotency-Key": commandId,
          },
          body: JSON.stringify({ reason }),
        },
      );
    },
    paymentExceptionRefundAudits(paymentNo, limit = 50) {
      return client.request<PaymentExceptionRefundAudit[]>(
        `/api/v1/payment/admin/payments/${encodeURIComponent(paymentNo)}`
        + `/exception-refunds/audits?limit=${encodeURIComponent(String(limit))}`,
      );
    },
  };
}
