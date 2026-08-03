import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createTradeApi,
  type AfterSale,
  type BusinessId,
  type TradeApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const LEGACY_PENDING_AFTER_SALE_KEY = "plain-journal:pending-after-sale:v1";
const PENDING_AFTER_SALE_KEY_PREFIX = "plain-journal:pending-after-sale:v2:";

export interface AfterSaleAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAfterSaleAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export interface PendingAfterSaleApplication {
  key: string;
  userId: BusinessId;
  orderNo: string;
  reason: string;
  createdAt: string;
}

class AfterSaleResponseMismatchError extends Error {
  constructor() {
    super("Trade 已响应，但返回的售后事实与本次账户或请求不一致。");
    this.name = "AfterSaleResponseMismatchError";
  }
}

function isActiveContext(context: AfterSaleAccessContext): context is {
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

function pendingKey(ownerId: BusinessId): string {
  return `${PENDING_AFTER_SALE_KEY_PREFIX}${ownerId}`;
}

function parsePending(
  raw: string | null,
  ownerId: BusinessId,
): PendingAfterSaleApplication | null {
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
      || !("reason" in value)
      || !("createdAt" in value)
      || typeof value.key !== "string"
      || value.userId !== ownerId
      || typeof value.orderNo !== "string"
      || value.orderNo.length === 0
      || typeof value.reason !== "string"
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingAfterSaleApplication;
  } catch {
    return null;
  }
}

function loadPending(ownerId: BusinessId): PendingAfterSaleApplication | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  const scoped = parsePending(localStorage.getItem(pendingKey(ownerId)), ownerId);
  if (scoped) {
    return scoped;
  }
  const legacy = parsePending(localStorage.getItem(LEGACY_PENDING_AFTER_SALE_KEY), ownerId);
  if (legacy) {
    localStorage.setItem(pendingKey(ownerId), JSON.stringify(legacy));
    localStorage.removeItem(LEGACY_PENDING_AFTER_SALE_KEY);
  }
  return legacy;
}

function applicationKey(): string {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `after-sale:${suffix}`;
}

function tradeApi(accessToken: string): TradeApi {
  return createTradeApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function isUncertain(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

function readError(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError && cause.status === 404) {
    return "售后记录不存在，或不属于当前账户。";
  }
  return cause instanceof Error ? cause.message : fallback;
}

export const useAfterSalesStore = defineStore("customer-after-sales", () => {
  const afterSales = ref<AfterSale[]>([]);
  const loading = ref(false);
  const loadingNo = ref<string | null>(null);
  const applyingOrderNo = ref<string | null>(null);
  const cancelingNo = ref<string | null>(null);
  const error = ref<string | null>(null);
  const applicationUnknown = ref(false);
  const applicationError = ref<string | null>(null);
  const cancellationUnknown = ref(false);
  const cancellationError = ref<string | null>(null);
  const pendingApplication = ref<PendingAfterSaleApplication | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let listRevision = 0;
  let detailRevision = 0;
  let applicationRevision = 0;
  let cancellationRevision = 0;
  let activeApplicationPromise: Promise<AfterSale | null> | null = null;
  let activeApplicationOrderNo: string | null = null;
  let activeApplicationAccessRevision = -1;
  let activeCancellationPromise: Promise<AfterSale | null> | null = null;
  let activeCancellationNo: string | null = null;
  let activeCancellationAccessRevision = -1;

  const currentPending = computed(() => pendingApplication.value);

  function synchronizeAccess(context: AfterSaleAccessContext): ActiveAfterSaleAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;

    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      listRevision += 1;
      detailRevision += 1;
      applicationRevision += 1;
      cancellationRevision += 1;
      activeApplicationPromise = null;
      activeApplicationOrderNo = null;
      activeApplicationAccessRevision = -1;
      activeCancellationPromise = null;
      activeCancellationNo = null;
      activeCancellationAccessRevision = -1;
      loading.value = false;
      loadingNo.value = null;
      applyingOrderNo.value = null;
      cancelingNo.value = null;
      error.value = null;
      applicationError.value = null;
      cancellationError.value = null;

      if (ownerChanged) {
        afterSales.value = [];
        pendingApplication.value = nextOwnerId ? loadPending(nextOwnerId) : null;
        applicationUnknown.value = Boolean(pendingApplication.value);
        cancellationUnknown.value = false;
      } else if (pendingApplication.value) {
        applicationUnknown.value = true;
        applicationError.value = "会话凭据已经更新，原申请键仍按当前账户保留；请重新查询 Trade 事实。";
      }
    }

    if (!isActiveContext(context)) {
      afterSales.value = [];
      pendingApplication.value = null;
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveAfterSaleAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function find(afterSaleNo: string): AfterSale | null {
    return afterSales.value.find((value) => value.afterSaleNo === afterSaleNo) ?? null;
  }

  function forOrder(orderNo: string): AfterSale | null {
    return afterSales.value.find((value) => value.orderNo === orderNo) ?? null;
  }

  function verifyFact(
    access: ActiveAfterSaleAccess,
    value: AfterSale,
    expected?: { afterSaleNo?: string; orderNo?: string },
  ) {
    if (
      value.userId !== access.ownerId
      || (expected?.afterSaleNo && value.afterSaleNo !== expected.afterSaleNo)
      || (expected?.orderNo && value.orderNo !== expected.orderNo)
    ) {
      throw new AfterSaleResponseMismatchError();
    }
  }

  function sortFacts() {
    afterSales.value.sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
  }

  function upsert(value: AfterSale) {
    const index = afterSales.value.findIndex((candidate) =>
      candidate.afterSaleNo === value.afterSaleNo || candidate.orderNo === value.orderNo);
    if (index >= 0) {
      afterSales.value[index] = value;
    } else {
      afterSales.value.push(value);
    }
    sortFacts();
  }

  function persistPending(
    access: ActiveAfterSaleAccess,
    value: PendingAfterSaleApplication | null,
  ) {
    if (!accessIsCurrent(access)) {
      return;
    }
    pendingApplication.value = value;
    if (typeof localStorage === "undefined") {
      return;
    }
    const key = pendingKey(access.ownerId);
    if (value) {
      localStorage.setItem(key, JSON.stringify(value));
    } else {
      localStorage.removeItem(key);
    }
  }

  function completeApplication(access: ActiveAfterSaleAccess, value: AfterSale) {
    if (!accessIsCurrent(access)) {
      return;
    }
    upsert(value);
    if (pendingApplication.value?.orderNo === value.orderNo) {
      persistPending(access, null);
    }
    applicationUnknown.value = false;
    applicationError.value = null;
  }

  async function loadForAccess(access: ActiveAfterSaleAccess): Promise<AfterSale[]> {
    const requestRevision = ++listRevision;
    loading.value = true;
    error.value = null;
    try {
      const values = await tradeApi(access.accessToken).afterSales();
      if (!accessIsCurrent(access) || requestRevision !== listRevision) {
        return afterSales.value;
      }
      for (const value of values) {
        verifyFact(access, value);
      }
      afterSales.value = values;
      sortFacts();
      const pending = pendingApplication.value;
      const recovered = pending ? forOrder(pending.orderNo) : null;
      if (recovered) {
        completeApplication(access, recovered);
      }
      return afterSales.value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        error.value = readError(cause, "售后列表暂时无法读取。");
      }
      return afterSales.value;
    } finally {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        loading.value = false;
      }
    }
  }

  async function load(context: AfterSaleAccessContext): Promise<AfterSale[]> {
    const access = synchronizeAccess(context);
    return access ? loadForAccess(access) : [];
  }

  async function loadOneForAccess(
    access: ActiveAfterSaleAccess,
    afterSaleNo: string,
  ): Promise<AfterSale | null> {
    const requestRevision = ++detailRevision;
    loadingNo.value = afterSaleNo;
    error.value = null;
    try {
      const value = await tradeApi(access.accessToken).afterSale(afterSaleNo);
      if (!accessIsCurrent(access) || requestRevision !== detailRevision) {
        return null;
      }
      verifyFact(access, value, { afterSaleNo });
      upsert(value);
      if (value.status === "CANCELED") {
        cancellationUnknown.value = false;
        cancellationError.value = null;
      }
      return value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === detailRevision) {
        error.value = readError(cause, "售后事实暂时无法读取。");
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === detailRevision) {
        loadingNo.value = null;
      }
    }
  }

  async function loadOne(
    context: AfterSaleAccessContext,
    afterSaleNo: string,
  ): Promise<AfterSale | null> {
    const access = synchronizeAccess(context);
    return access ? loadOneForAccess(access, afterSaleNo) : null;
  }

  function prepare(
    access: ActiveAfterSaleAccess,
    orderNo: string,
    reason: string,
  ): PendingAfterSaleApplication | null {
    const trimmedReason = reason.trim();
    if (!trimmedReason) {
      applicationError.value = "请说明申请整单退货退款的真实原因。";
      return null;
    }
    const existing = pendingApplication.value;
    if (existing) {
      if (existing.orderNo !== orderNo) {
        applicationUnknown.value = true;
        applicationError.value = "当前账户已有另一笔售后申请结果尚未确认，请先处理原订单。";
        return null;
      }
      return existing;
    }
    const value: PendingAfterSaleApplication = {
      key: applicationKey(),
      userId: access.ownerId,
      orderNo,
      reason: trimmedReason,
      createdAt: new Date().toISOString(),
    };
    persistPending(access, value);
    return value;
  }

  async function recoverApplicationForAccess(
    access: ActiveAfterSaleAccess,
    orderNo: string,
  ): Promise<AfterSale | null> {
    await loadForAccess(access);
    if (!accessIsCurrent(access)) {
      return null;
    }
    const recovered = forOrder(orderNo);
    if (recovered) {
      completeApplication(access, recovered);
    }
    return recovered;
  }

  function apply(
    context: AfterSaleAccessContext,
    orderNo: string,
    reason: string,
  ): Promise<AfterSale | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      applicationError.value = "当前会话没有可用于申请售后的顾客事实。";
      return Promise.resolve(null);
    }
    if (
      activeApplicationPromise
      && activeApplicationOrderNo === orderNo
      && activeApplicationAccessRevision === access.revision
    ) {
      return activeApplicationPromise;
    }
    const request = applyForAccess(access, orderNo, reason);
    activeApplicationPromise = request;
    activeApplicationOrderNo = orderNo;
    activeApplicationAccessRevision = access.revision;
    const cleanup = () => {
      if (activeApplicationPromise === request) {
        activeApplicationPromise = null;
        activeApplicationOrderNo = null;
        activeApplicationAccessRevision = -1;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function applyForAccess(
    access: ActiveAfterSaleAccess,
    orderNo: string,
    reason: string,
  ): Promise<AfterSale | null> {
    applicationError.value = null;
    const known = forOrder(orderNo);
    if (known) {
      return known;
    }
    if (pendingApplication.value) {
      const recovered = await recoverApplicationForAccess(access, orderNo);
      if (!accessIsCurrent(access) || recovered) {
        return recovered;
      }
    }
    const pending = prepare(access, orderNo, reason);
    if (!pending) {
      return null;
    }
    const requestRevision = ++applicationRevision;
    applyingOrderNo.value = orderNo;
    try {
      const value = await tradeApi(access.accessToken).applyAfterSale(
        pending.orderNo,
        pending.reason,
        pending.key,
      );
      if (!accessIsCurrent(access) || requestRevision !== applicationRevision) {
        return null;
      }
      verifyFact(access, value, { orderNo });
      completeApplication(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== applicationRevision) {
        return null;
      }
      if (isUncertain(cause)) {
        const recovered = await recoverApplicationForAccess(access, orderNo);
        if (recovered) {
          return recovered;
        }
        applicationUnknown.value = true;
        applicationError.value = "售后申请结果尚未确认。原申请键已按当前账户保留，请查询或安全重试。";
        return null;
      }
      if (cause instanceof AfterSaleResponseMismatchError) {
        applicationUnknown.value = true;
        applicationError.value = cause.message;
        return null;
      }
      persistPending(access, null);
      applicationUnknown.value = false;
      applicationError.value = cause instanceof Error ? cause.message : "售后申请未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === applicationRevision) {
        applyingOrderNo.value = null;
      }
    }
  }

  function cancel(
    context: AfterSaleAccessContext,
    afterSaleNo: string,
  ): Promise<AfterSale | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      cancellationError.value = "当前会话没有可用于取消售后的顾客事实。";
      return Promise.resolve(null);
    }
    if (
      activeCancellationPromise
      && activeCancellationNo === afterSaleNo
      && activeCancellationAccessRevision === access.revision
    ) {
      return activeCancellationPromise;
    }
    const request = cancelForAccess(access, afterSaleNo);
    activeCancellationPromise = request;
    activeCancellationNo = afterSaleNo;
    activeCancellationAccessRevision = access.revision;
    const cleanup = () => {
      if (activeCancellationPromise === request) {
        activeCancellationPromise = null;
        activeCancellationNo = null;
        activeCancellationAccessRevision = -1;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function cancelForAccess(
    access: ActiveAfterSaleAccess,
    afterSaleNo: string,
  ): Promise<AfterSale | null> {
    cancellationError.value = null;
    let current = find(afterSaleNo);
    if (!current) {
      current = await loadOneForAccess(access, afterSaleNo);
    }
    if (!accessIsCurrent(access) || !current) {
      return null;
    }
    if (current.status === "CANCELED") {
      cancellationUnknown.value = false;
      return current;
    }
    if (current.status !== "APPLIED") {
      cancellationUnknown.value = false;
      cancellationError.value = `售后当前为 ${current.status}，不能取消已经推进的退货或退款。`;
      return current;
    }

    const requestRevision = ++cancellationRevision;
    cancelingNo.value = afterSaleNo;
    try {
      const value = await tradeApi(access.accessToken).cancelAfterSale(afterSaleNo);
      if (!accessIsCurrent(access) || requestRevision !== cancellationRevision) {
        return null;
      }
      verifyFact(access, value, { afterSaleNo });
      upsert(value);
      cancellationUnknown.value = value.status !== "CANCELED";
      cancellationError.value = value.status === "CANCELED"
        ? null
        : "取消接口已响应，但 Trade 尚未返回 CANCELED；请继续查询售后事实。";
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== cancellationRevision) {
        return null;
      }
      if (isUncertain(cause)) {
        const recovered = await loadOneForAccess(access, afterSaleNo);
        if (recovered?.status === "CANCELED") {
          cancellationUnknown.value = false;
          cancellationError.value = null;
          return recovered;
        }
        cancellationUnknown.value = true;
        cancellationError.value = "取消售后结果尚未确认，请查询 Trade 事实后再决定是否重试。";
        return recovered;
      }
      cancellationUnknown.value = false;
      cancellationError.value = cause instanceof Error ? cause.message : "取消售后未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === cancellationRevision) {
        cancelingNo.value = null;
      }
    }
  }

  return {
    afterSales,
    loading,
    loadingNo,
    applyingOrderNo,
    cancelingNo,
    error,
    applicationUnknown,
    applicationError,
    cancellationUnknown,
    cancellationError,
    pendingApplication,
    currentPending,
    activeOwnerId,
    synchronizeAccess,
    find,
    forOrder,
    load,
    loadOne,
    apply,
    cancel,
  };
});
