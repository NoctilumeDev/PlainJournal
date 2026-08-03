import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createTradeApi,
  type BusinessId,
  type Order,
  type TradeApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const LEGACY_PENDING_CANCELLATION_KEY = "plain-journal:pending-order-cancellation:v1";
const PENDING_CANCELLATION_KEY_PREFIX = "plain-journal:pending-order-cancellation:v2:";
const ORDER_PAGE_SIZE = 20;

export interface OrderAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveOrderAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export interface PendingOrderCancellation {
  userId: BusinessId;
  orderNo: string;
  createdAt: string;
}

export class OrderAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的订单请求结果不会写入当前页面。");
    this.name = "OrderAccessChangedError";
  }
}

function isActiveContext(context: OrderAccessContext): context is {
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

function pendingCancellationKey(ownerId: BusinessId): string {
  return `${PENDING_CANCELLATION_KEY_PREFIX}${ownerId}`;
}

function parsePendingCancellation(
  raw: string | null,
  ownerId: BusinessId,
): PendingOrderCancellation | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("userId" in value)
      || !("orderNo" in value)
      || !("createdAt" in value)
      || value.userId !== ownerId
      || typeof value.orderNo !== "string"
      || value.orderNo.length === 0
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingOrderCancellation;
  } catch {
    return null;
  }
}

function loadPendingCancellation(ownerId: BusinessId): PendingOrderCancellation | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  const scopedKey = pendingCancellationKey(ownerId);
  const scoped = parsePendingCancellation(localStorage.getItem(scopedKey), ownerId);
  if (scoped) {
    return scoped;
  }
  const legacy = parsePendingCancellation(
    localStorage.getItem(LEGACY_PENDING_CANCELLATION_KEY),
    ownerId,
  );
  if (legacy) {
    localStorage.setItem(scopedKey, JSON.stringify(legacy));
    localStorage.removeItem(LEGACY_PENDING_CANCELLATION_KEY);
  }
  return legacy;
}

function tradeApi(accessToken: string): TradeApi {
  return createTradeApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function isUncertainCancellationFailure(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

function orderReadError(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError && cause.status === 404) {
    return "订单不存在，或不属于当前账户。";
  }
  return cause instanceof Error ? cause.message : fallback;
}

export const useOrdersStore = defineStore("customer-orders", () => {
  const orders = ref<Order[]>([]);
  const page = ref(1);
  const total = ref(0);
  const loading = ref(false);
  const loadingMore = ref(false);
  const error = ref<string | null>(null);
  const cancelingOrderNo = ref<string | null>(null);
  const resolvingCancellation = ref(false);
  const pendingCancellation = ref<PendingOrderCancellation | null>(null);
  const cancellationUnknown = ref(false);
  const cancellationError = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let listRevision = 0;
  let detailRevision = 0;
  let cancellationRevision = 0;
  let activeCancellationPromise: Promise<Order | null> | null = null;
  let activeCancellationOrderNo: string | null = null;
  let activeCancellationAccessRevision = -1;

  const currentAccountPendingCancellation = computed(() => pendingCancellation.value);
  const hasMore = computed(() => orders.value.length < total.value);

  function synchronizeAccess(context: OrderAccessContext): ActiveOrderAccess | null {
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
      cancellationRevision += 1;
      activeCancellationPromise = null;
      activeCancellationOrderNo = null;
      activeCancellationAccessRevision = -1;
      loading.value = false;
      loadingMore.value = false;
      cancelingOrderNo.value = null;
      resolvingCancellation.value = false;
      error.value = null;

      if (ownerChanged) {
        orders.value = [];
        page.value = 1;
        total.value = 0;
        pendingCancellation.value = nextOwnerId
          ? loadPendingCancellation(nextOwnerId)
          : null;
        cancellationUnknown.value = Boolean(pendingCancellation.value);
        cancellationError.value = null;
      } else if (pendingCancellation.value) {
        cancellationUnknown.value = true;
        cancellationError.value = "会话凭据更新时取消结果仍待确认，请重新查询 Trade 事实。";
      }
    }

    if (!isActiveContext(context)) {
      orders.value = [];
      pendingCancellation.value = null;
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveOrderAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveOrderAccess) {
    if (!accessIsCurrent(access)) {
      throw new OrderAccessChangedError();
    }
  }

  function order(orderNo: string): Order | null {
    return orders.value.find((candidate) => candidate.orderNo === orderNo) ?? null;
  }

  function sortOrders() {
    orders.value.sort((left, right) =>
      Date.parse(right.createdAt) - Date.parse(left.createdAt));
  }

  function upsertOrder(value: Order) {
    const index = orders.value.findIndex((candidate) =>
      candidate.orderNo === value.orderNo);
    if (index >= 0) {
      orders.value[index] = value;
    } else {
      orders.value.push(value);
    }
    sortOrders();
  }

  function persistPendingCancellation(
    access: ActiveOrderAccess,
    value: PendingOrderCancellation | null,
  ) {
    requireCurrent(access);
    pendingCancellation.value = value;
    if (typeof localStorage === "undefined") {
      return;
    }
    const key = pendingCancellationKey(access.ownerId);
    if (value) {
      localStorage.setItem(key, JSON.stringify(value));
    } else {
      localStorage.removeItem(key);
    }
  }

  function reconcilePendingFact(
    access: ActiveOrderAccess,
    value: Order,
    silent = false,
  ) {
    requireCurrent(access);
    const pending = pendingCancellation.value;
    if (!pending || pending.orderNo !== value.orderNo) {
      return;
    }
    if (value.status === "CANCELING" || value.status === "CANCELED") {
      persistPendingCancellation(access, null);
      cancellationUnknown.value = false;
      cancellationError.value = null;
      return;
    }
    if (value.status === "PENDING_PAYMENT") {
      cancellationUnknown.value = true;
      if (!silent) {
        cancellationError.value = "Trade 仍返回待支付。取消结果尚未确认，可以使用同一路径安全重试。";
      }
      return;
    }
    persistPendingCancellation(access, null);
    cancellationUnknown.value = false;
    cancellationError.value = `订单当前为 ${value.status}，取消没有被解释为完成。`;
  }

  async function load(context: OrderAccessContext): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      return;
    }
    const requestRevision = ++listRevision;
    loading.value = true;
    error.value = null;
    let loaded = false;
    try {
      const result = await tradeApi(access.accessToken).orders(1, ORDER_PAGE_SIZE);
      requireCurrent(access);
      if (requestRevision !== listRevision) {
        return;
      }
      orders.value = result.items;
      page.value = result.page;
      total.value = result.total;
      sortOrders();
      loaded = true;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new OrderAccessChangedError();
      }
      if (requestRevision === listRevision) {
        error.value = cause instanceof Error ? cause.message : "订单列表暂时无法读取。";
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        loading.value = false;
      }
    }

    const pending = pendingCancellation.value;
    if (!loaded || !pending || !accessIsCurrent(access)) {
      return;
    }
    const current = order(pending.orderNo);
    if (current) {
      reconcilePendingFact(access, current);
      return;
    }
    await recoverPendingCancellationForAccess(access, true);
  }

  async function loadMore(context: OrderAccessContext): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access || loadingMore.value || !hasMore.value) {
      return;
    }
    const requestRevision = ++listRevision;
    loadingMore.value = true;
    error.value = null;
    try {
      const result = await tradeApi(access.accessToken).orders(page.value + 1, ORDER_PAGE_SIZE);
      requireCurrent(access);
      if (requestRevision !== listRevision) {
        return;
      }
      for (const value of result.items) {
        upsertOrder(value);
      }
      page.value = result.page;
      total.value = result.total;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new OrderAccessChangedError();
      }
      if (requestRevision === listRevision) {
        error.value = cause instanceof Error ? cause.message : "更多订单暂时无法读取。";
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        loadingMore.value = false;
      }
    }
  }

  async function loadOrder(
    context: OrderAccessContext,
    orderNo: string,
  ): Promise<Order | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const requestRevision = ++detailRevision;
    loading.value = true;
    error.value = null;
    try {
      const value = await tradeApi(access.accessToken).order(orderNo);
      requireCurrent(access);
      if (requestRevision !== detailRevision) {
        return null;
      }
      upsertOrder(value);
      reconcilePendingFact(access, value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new OrderAccessChangedError();
      }
      if (requestRevision === detailRevision) {
        error.value = orderReadError(cause, "订单事实暂时无法读取。");
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === detailRevision) {
        loading.value = false;
      }
    }
  }

  function preparePendingCancellation(
    access: ActiveOrderAccess,
    orderNo: string,
  ): PendingOrderCancellation | null {
    const existing = pendingCancellation.value;
    if (existing) {
      if (existing.orderNo !== orderNo) {
        cancellationUnknown.value = true;
        cancellationError.value = "已有另一笔订单的取消结果尚未确认，请先处理原订单。";
        return null;
      }
      return existing;
    }
    const pending: PendingOrderCancellation = {
      userId: access.ownerId,
      orderNo,
      createdAt: new Date().toISOString(),
    };
    persistPendingCancellation(access, pending);
    return pending;
  }

  async function recoverPendingCancellationForAccess(
    access: ActiveOrderAccess,
    silent = false,
  ): Promise<Order | null> {
    const pending = pendingCancellation.value;
    if (!pending) {
      return null;
    }
    const requestRevision = ++cancellationRevision;
    resolvingCancellation.value = true;
    try {
      const value = await tradeApi(access.accessToken).order(pending.orderNo);
      requireCurrent(access);
      if (requestRevision !== cancellationRevision) {
        return null;
      }
      upsertOrder(value);
      reconcilePendingFact(access, value, silent);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new OrderAccessChangedError();
      }
      if (requestRevision === cancellationRevision) {
        cancellationUnknown.value = true;
        cancellationError.value = silent
          ? "取消结果仍无法查询。原订单记录已保留，可以稍后再次确认。"
          : cause instanceof Error
            ? `取消结果查询未完成：${cause.message}`
            : "取消结果查询未完成。";
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === cancellationRevision) {
        resolvingCancellation.value = false;
      }
    }
  }

  async function recoverPendingCancellation(
    context: OrderAccessContext,
    silent = false,
  ): Promise<Order | null> {
    const access = synchronizeAccess(context);
    return access ? recoverPendingCancellationForAccess(access, silent) : null;
  }

  function cancelOrder(
    context: OrderAccessContext,
    orderNo: string,
  ): Promise<Order | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      cancellationError.value = "当前会话没有可用于取消订单的用户事实。";
      return Promise.resolve(null);
    }
    if (
      activeCancellationPromise
      && activeCancellationOrderNo === orderNo
      && activeCancellationAccessRevision === access.revision
    ) {
      return activeCancellationPromise;
    }

    const request = cancelOrderForAccess(access, orderNo);
    activeCancellationPromise = request;
    activeCancellationOrderNo = orderNo;
    activeCancellationAccessRevision = access.revision;
    void request.finally(() => {
      if (
        activeCancellationPromise === request
        && activeCancellationAccessRevision === access.revision
      ) {
        activeCancellationPromise = null;
        activeCancellationOrderNo = null;
        activeCancellationAccessRevision = -1;
      }
    });
    return request;
  }

  async function cancelOrderForAccess(
    access: ActiveOrderAccess,
    orderNo: string,
  ): Promise<Order | null> {
    cancellationError.value = null;
    let current = order(orderNo);
    if (!current) {
      current = await loadOrder({
        authenticated: true,
        ownerId: access.ownerId,
        accessToken: access.accessToken,
      }, orderNo);
    }
    requireCurrent(access);
    if (!current) {
      return null;
    }
    if (current.status !== "PENDING_PAYMENT") {
      if (current.status === "CANCELING" || current.status === "CANCELED") {
        reconcilePendingFact(access, current);
        return current;
      }
      cancellationError.value = `订单当前为 ${current.status}，不能发起顾客取消。`;
      return current;
    }

    if (pendingCancellation.value) {
      const recovered = await recoverPendingCancellationForAccess(access, true);
      requireCurrent(access);
      if (recovered && recovered.status !== "PENDING_PAYMENT") {
        return recovered;
      }
    }
    if (!preparePendingCancellation(access, orderNo)) {
      return null;
    }

    const requestRevision = ++cancellationRevision;
    cancelingOrderNo.value = orderNo;
    try {
      const value = await tradeApi(access.accessToken).cancelOrder(orderNo);
      requireCurrent(access);
      if (requestRevision !== cancellationRevision) {
        return null;
      }
      upsertOrder(value);
      reconcilePendingFact(access, value);
      if (value.status === "PENDING_PAYMENT") {
        cancellationUnknown.value = true;
        cancellationError.value = "取消接口已响应，但 Trade 仍返回待支付。请查询或使用同一路径安全重试。";
      }
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new OrderAccessChangedError();
      }
      if (requestRevision !== cancellationRevision) {
        return null;
      }
      if (isUncertainCancellationFailure(cause)) {
        const recovered = await recoverPendingCancellationForAccess(access, true);
        requireCurrent(access);
        if (recovered && (
          recovered.status === "CANCELING"
          || recovered.status === "CANCELED"
        )) {
          return recovered;
        }
        cancellationUnknown.value = true;
        cancellationError.value = "取消请求结果尚未确认。订单记录已保留，请查询或使用同一路径安全重试。";
        return recovered;
      }
      persistPendingCancellation(access, null);
      cancellationUnknown.value = false;
      cancellationError.value = cause instanceof Error ? cause.message : "订单取消未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === cancellationRevision) {
        cancelingOrderNo.value = null;
      }
    }
  }

  return {
    orders,
    page,
    total,
    hasMore,
    loading,
    loadingMore,
    error,
    cancelingOrderNo,
    resolvingCancellation,
    pendingCancellation,
    currentAccountPendingCancellation,
    cancellationUnknown,
    cancellationError,
    activeOwnerId,
    synchronizeAccess,
    order,
    load,
    loadMore,
    loadOrder,
    recoverPendingCancellation,
    cancelOrder,
  };
});
