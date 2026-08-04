import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createPaymentApi,
  secureRandomUUID,
  type BusinessId,
  type Payment,
  type PaymentApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const LEGACY_PENDING_PAYMENT_KEY = "plain-journal:pending-payment:v1";
const PENDING_PAYMENT_KEY_PREFIX = "plain-journal:pending-payment:v2:";

export interface PaymentAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActivePaymentAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export interface PendingPaymentSubmission {
  key: string;
  userId: BusinessId;
  orderNo: string;
  channel: "MOCK";
  createdAt: string;
}

export class PaymentAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的支付请求结果不会写入当前页面。");
    this.name = "PaymentAccessChangedError";
  }
}

class PaymentResponseMismatchError extends Error {
  constructor() {
    super("Payment 已响应，但返回的支付事实与本次请求不一致。");
    this.name = "PaymentResponseMismatchError";
  }
}

function isActiveContext(context: PaymentAccessContext): context is {
  authenticated: true;
  ownerId: BusinessId;
  accessToken: string;
} {
  return context.authenticated
    && typeof context.ownerId === "string"
    && context.ownerId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function pendingPaymentKey(ownerId: BusinessId): string {
  return `${PENDING_PAYMENT_KEY_PREFIX}${ownerId}`;
}

function parsePendingPayment(
  raw: string | null,
  ownerId: BusinessId,
): PendingPaymentSubmission | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("key" in value)
      || !("userId" in value)
      || !("orderNo" in value)
      || !("channel" in value)
      || !("createdAt" in value)
      || typeof value.key !== "string"
      || value.userId !== ownerId
      || typeof value.orderNo !== "string"
      || value.channel !== "MOCK"
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingPaymentSubmission;
  } catch {
    return null;
  }
}

function loadPendingPayment(ownerId: BusinessId): PendingPaymentSubmission | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  const scopedKey = pendingPaymentKey(ownerId);
  const scoped = parsePendingPayment(localStorage.getItem(scopedKey), ownerId);
  if (scoped) {
    return scoped;
  }
  const legacy = parsePendingPayment(
    localStorage.getItem(LEGACY_PENDING_PAYMENT_KEY),
    ownerId,
  );
  if (legacy) {
    localStorage.setItem(scopedKey, JSON.stringify(legacy));
    localStorage.removeItem(LEGACY_PENDING_PAYMENT_KEY);
  }
  return legacy;
}

function newPaymentKey(): string {
  return `payment:${secureRandomUUID()}`;
}

function paymentApi(accessToken: string): PaymentApi {
  return createPaymentApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function isUncertainPaymentFailure(cause: unknown): boolean {
  return cause instanceof PaymentResponseMismatchError
    || (cause instanceof ApiError && (
      cause.kind === "network"
      || cause.kind === "timeout"
      || cause.kind === "invalid-response"
      || (cause.kind === "http" && (cause.status ?? 500) >= 500)
    ));
}

export const usePaymentsStore = defineStore("customer-payments", () => {
  const payments = ref<Payment[]>([]);
  const loadingOrderNo = ref<string | null>(null);
  const creatingOrderNo = ref<string | null>(null);
  const refreshingPaymentNo = ref<string | null>(null);
  const resolvingSubmission = ref(false);
  const error = ref<string | null>(null);
  const submissionUnknown = ref(false);
  const submissionError = ref<string | null>(null);
  const pendingSubmission = ref<PendingPaymentSubmission | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let readRevision = 0;
  let createRevision = 0;
  let submissionRevision = 0;
  let activeCreatePromise: Promise<Payment | null> | null = null;
  let activeCreateOrderNo: string | null = null;
  let activeCreateAccessRevision = -1;
  const knownAbsentOrderNos = new Set<string>();

  const currentAccountPendingSubmission = computed(() => pendingSubmission.value);

  function synchronizeAccess(context: PaymentAccessContext): ActivePaymentAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;

    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      readRevision += 1;
      createRevision += 1;
      submissionRevision += 1;
      activeCreatePromise = null;
      activeCreateOrderNo = null;
      activeCreateAccessRevision = -1;
      loadingOrderNo.value = null;
      creatingOrderNo.value = null;
      refreshingPaymentNo.value = null;
      resolvingSubmission.value = false;
      error.value = null;

      if (ownerChanged) {
        payments.value = [];
        knownAbsentOrderNos.clear();
        pendingSubmission.value = nextOwnerId ? loadPendingPayment(nextOwnerId) : null;
        submissionUnknown.value = Boolean(pendingSubmission.value);
        submissionError.value = null;
      } else if (pendingSubmission.value) {
        submissionUnknown.value = true;
        submissionError.value = "会话凭据更新时支付结果仍待确认，请重新查询 Payment 事实。";
      }
    }

    if (!isActiveContext(context)) {
      payments.value = [];
      pendingSubmission.value = null;
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActivePaymentAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActivePaymentAccess) {
    if (!accessIsCurrent(access)) {
      throw new PaymentAccessChangedError();
    }
  }

  function paymentForOrder(orderNo: string): Payment | null {
    return payments.value.find((payment) => payment.orderNo === orderNo) ?? null;
  }

  function upsertPayment(value: Payment) {
    knownAbsentOrderNos.delete(value.orderNo);
    const index = payments.value.findIndex((payment) =>
      payment.paymentNo === value.paymentNo || payment.orderNo === value.orderNo);
    if (index >= 0) {
      payments.value[index] = value;
    } else {
      payments.value.push(value);
    }
  }

  function persistPendingSubmission(
    access: ActivePaymentAccess,
    value: PendingPaymentSubmission | null,
  ) {
    requireCurrent(access);
    pendingSubmission.value = value;
    if (typeof localStorage === "undefined") {
      return;
    }
    const key = pendingPaymentKey(access.ownerId);
    if (value) {
      localStorage.setItem(key, JSON.stringify(value));
    } else {
      localStorage.removeItem(key);
    }
  }

  function completeSubmission(access: ActivePaymentAccess, value: Payment) {
    requireCurrent(access);
    upsertPayment(value);
    const pending = pendingSubmission.value;
    if (pending && pending.orderNo !== value.orderNo) {
      return;
    }
    if (pending) {
      persistPendingSubmission(access, null);
    }
    submissionUnknown.value = false;
    submissionError.value = null;
  }

  async function loadForOrder(
    context: PaymentAccessContext,
    orderNo: string,
    silentNotFound = true,
  ): Promise<Payment | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const requestRevision = ++readRevision;
    loadingOrderNo.value = orderNo;
    error.value = null;
    try {
      const value = await paymentApi(access.accessToken).paymentByOrder(orderNo);
      requireCurrent(access);
      if (requestRevision !== readRevision) {
        return null;
      }
      if (value.orderNo !== orderNo) {
        throw new PaymentResponseMismatchError();
      }
      completeSubmission(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new PaymentAccessChangedError();
      }
      if (requestRevision !== readRevision) {
        return null;
      }
      if (silentNotFound && cause instanceof ApiError && cause.status === 404) {
        knownAbsentOrderNos.add(orderNo);
        return null;
      }
      error.value = cause instanceof Error ? cause.message : "支付事实暂时无法读取。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === readRevision) {
        loadingOrderNo.value = null;
      }
    }
  }

  async function refreshPayment(
    context: PaymentAccessContext,
    paymentNo: string,
  ): Promise<Payment | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const requestRevision = ++readRevision;
    refreshingPaymentNo.value = paymentNo;
    error.value = null;
    try {
      const value = await paymentApi(access.accessToken).payment(paymentNo);
      requireCurrent(access);
      if (requestRevision !== readRevision) {
        return null;
      }
      if (value.paymentNo !== paymentNo) {
        throw new PaymentResponseMismatchError();
      }
      completeSubmission(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new PaymentAccessChangedError();
      }
      if (requestRevision === readRevision) {
        error.value = cause instanceof Error ? cause.message : "支付状态暂时无法刷新。";
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === readRevision) {
        refreshingPaymentNo.value = null;
      }
    }
  }

  async function recoverPendingSubmissionForAccess(
    access: ActivePaymentAccess,
    silent = false,
  ): Promise<Payment | null> {
    const pending = pendingSubmission.value;
    if (!pending) {
      return null;
    }
    const requestRevision = ++submissionRevision;
    resolvingSubmission.value = true;
    try {
      const value = await paymentApi(access.accessToken)
        .paymentByIdempotencyKey(pending.key);
      requireCurrent(access);
      if (requestRevision !== submissionRevision) {
        return null;
      }
      if (value.orderNo !== pending.orderNo) {
        throw new PaymentResponseMismatchError();
      }
      completeSubmission(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new PaymentAccessChangedError();
      }
      if (requestRevision !== submissionRevision) {
        return null;
      }
      submissionUnknown.value = true;
      if (cause instanceof ApiError && cause.status === 404) {
        if (!silent) {
          submissionError.value = "暂未查询到支付单。可以使用原支付键安全重试，不能换键创建第二笔支付。";
        }
        return null;
      }
      submissionError.value = cause instanceof Error
        ? `支付结果查询未完成：${cause.message}`
        : "支付结果查询未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === submissionRevision) {
        resolvingSubmission.value = false;
      }
    }
  }

  async function recoverPendingSubmission(
    context: PaymentAccessContext,
    silent = false,
  ): Promise<Payment | null> {
    const access = synchronizeAccess(context);
    return access ? recoverPendingSubmissionForAccess(access, silent) : null;
  }

  function prepareSubmission(
    access: ActivePaymentAccess,
    orderNo: string,
  ): PendingPaymentSubmission | null {
    const existing = pendingSubmission.value;
    if (existing) {
      if (existing.orderNo !== orderNo) {
        submissionUnknown.value = true;
        submissionError.value = "已有另一笔支付结果尚未确认，请先处理原订单。";
        return null;
      }
      return existing;
    }
    const pending: PendingPaymentSubmission = {
      key: newPaymentKey(),
      userId: access.ownerId,
      orderNo,
      channel: "MOCK",
      createdAt: new Date().toISOString(),
    };
    persistPendingSubmission(access, pending);
    return pending;
  }

  function createForOrder(
    context: PaymentAccessContext,
    orderNo: string,
  ): Promise<Payment | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      submissionError.value = "当前会话没有可用于创建支付单的用户事实。";
      return Promise.resolve(null);
    }
    if (
      activeCreatePromise
      && activeCreateOrderNo === orderNo
      && activeCreateAccessRevision === access.revision
    ) {
      return activeCreatePromise;
    }

    const request = createForOrderWithAccess(access, orderNo);
    activeCreatePromise = request;
    activeCreateOrderNo = orderNo;
    activeCreateAccessRevision = access.revision;
    const clearActiveCreate = () => {
      if (
        activeCreatePromise === request
        && activeCreateAccessRevision === access.revision
      ) {
        activeCreatePromise = null;
        activeCreateOrderNo = null;
        activeCreateAccessRevision = -1;
      }
    };
    void request.then(clearActiveCreate, clearActiveCreate);
    return request;
  }

  async function createForOrderWithAccess(
    access: ActivePaymentAccess,
    orderNo: string,
  ): Promise<Payment | null> {
    submissionError.value = null;
    const known = paymentForOrder(orderNo) ?? (
      knownAbsentOrderNos.has(orderNo)
        ? null
        : await loadForOrder({
          authenticated: true,
          ownerId: access.ownerId,
          accessToken: access.accessToken,
        }, orderNo, true)
    );
    requireCurrent(access);
    if (known) {
      return known;
    }

    if (pendingSubmission.value) {
      const recovered = await recoverPendingSubmissionForAccess(access, true);
      requireCurrent(access);
      if (recovered) {
        return recovered;
      }
    }
    const pending = prepareSubmission(access, orderNo);
    if (!pending) {
      return null;
    }

    const requestRevision = ++createRevision;
    creatingOrderNo.value = orderNo;
    try {
      const value = await paymentApi(access.accessToken).createPayment({
        orderNo: pending.orderNo,
        channel: pending.channel,
      }, pending.key);
      requireCurrent(access);
      if (requestRevision !== createRevision) {
        return null;
      }
      if (value.orderNo !== orderNo) {
        throw new PaymentResponseMismatchError();
      }
      completeSubmission(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new PaymentAccessChangedError();
      }
      if (requestRevision !== createRevision) {
        return null;
      }
      if (isUncertainPaymentFailure(cause)) {
        const recovered = await recoverPendingSubmissionForAccess(access, true);
        requireCurrent(access);
        if (recovered) {
          return recovered;
        }
        submissionUnknown.value = true;
        submissionError.value = "支付创建结果尚未确认。原支付键已保留，请查询或使用原键安全重试。";
        return null;
      }
      persistPendingSubmission(access, null);
      submissionUnknown.value = false;
      submissionError.value = cause instanceof Error ? cause.message : "支付单创建未完成。";
      return null;
    } finally {
      if (
        accessIsCurrent(access)
        && requestRevision === createRevision
        && creatingOrderNo.value === orderNo
      ) {
        creatingOrderNo.value = null;
      }
    }
  }

  return {
    payments,
    loadingOrderNo,
    creatingOrderNo,
    refreshingPaymentNo,
    resolvingSubmission,
    error,
    submissionUnknown,
    submissionError,
    pendingSubmission,
    currentAccountPendingSubmission,
    activeOwnerId,
    synchronizeAccess,
    paymentForOrder,
    loadForOrder,
    refreshPayment,
    recoverPendingSubmission,
    createForOrder,
  };
});
