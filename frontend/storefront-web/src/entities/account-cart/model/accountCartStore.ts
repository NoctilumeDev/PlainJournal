import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createTradeApi,
  multiplyMoney,
  sumMoney,
  type BusinessId,
  type CartItem,
  type PutCartItemInput,
  type TradeApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface AccountCartAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccountCartAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export type AccountCartMutationStatus =
  | "idle"
  | "pending"
  | "succeeded"
  | "unknown"
  | "failed";

export class AccountCartAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的购物车请求结果不会写入当前页面。");
    this.name = "AccountCartAccessChangedError";
  }
}

export class AccountCartMutationBusyError extends Error {
  constructor() {
    super("上一项购物车修改仍在确认，请稍候。");
    this.name = "AccountCartMutationBusyError";
  }
}

class AccountCartResponseMismatchError extends Error {
  constructor() {
    super("Trade 已响应，但返回的购物车项与本次请求不一致。");
    this.name = "AccountCartResponseMismatchError";
  }
}

function isActiveContext(context: AccountCartAccessContext): context is {
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

function tradeApi(accessToken: string): TradeApi {
  return createTradeApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function resultMayBeUnknown(cause: unknown): boolean {
  return cause instanceof AccountCartResponseMismatchError
    || (cause instanceof ApiError && (
      cause.kind === "network"
      || cause.kind === "timeout"
      || cause.kind === "invalid-response"
      || (cause.kind === "http" && (cause.status ?? 500) >= 500)
    ));
}

export const useAccountCartStore = defineStore("account-cart", () => {
  const items = ref<CartItem[]>([]);
  const loading = ref(false);
  const mutatingSkuId = ref<BusinessId | null>(null);
  const error = ref<string | null>(null);
  const mutationStatus = ref<AccountCartMutationStatus>("idle");
  const mutationMessage = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let loadRevision = 0;
  let mutationRevision = 0;
  let activeLoadPromise: Promise<void> | null = null;
  let activeLoadAccessRevision = -1;

  const selectedItems = computed(() => items.value.filter((item) => item.selected));
  const itemCount = computed(() => items.value.reduce(
    (total, item) => total + item.quantity,
    0,
  ));
  const selectedItemCount = computed(() => selectedItems.value.reduce(
    (total, item) => total + item.quantity,
    0,
  ));
  const selectedSubtotal = computed(() => sumMoney(
    selectedItems.value.map((item) => multiplyMoney(item.unitPrice, item.quantity)),
  ));
  const mutating = computed(() => mutatingSkuId.value !== null);

  function synchronizeAccess(
    context: AccountCartAccessContext,
  ): ActiveAccountCartAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;
    const accessChanged = ownerChanged || tokenChanged;

    if (accessChanged) {
      const interruptedMutation = mutatingSkuId.value !== null;
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      loadRevision += 1;
      mutationRevision += 1;
      activeLoadPromise = null;
      activeLoadAccessRevision = -1;
      loading.value = false;
      mutatingSkuId.value = null;
      error.value = null;

      if (ownerChanged) {
        items.value = [];
        mutationStatus.value = "idle";
        mutationMessage.value = null;
      } else if (interruptedMutation) {
        mutationStatus.value = "unknown";
        mutationMessage.value = "会话凭据更新时购物车修改仍在进行，请重新读取 Trade 事实。";
      }
    }

    if (!isActiveContext(context)) {
      items.value = [];
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveAccountCartAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveAccountCartAccess) {
    if (!accessIsCurrent(access)) {
      throw new AccountCartAccessChangedError();
    }
  }

  function load(
    context: AccountCartAccessContext,
    options: { force?: boolean } = {},
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      return Promise.resolve();
    }
    if (
      !options.force
      && activeLoadPromise
      && activeLoadAccessRevision === access.revision
    ) {
      return activeLoadPromise;
    }
    if (mutatingSkuId.value) {
      return Promise.reject(new AccountCartMutationBusyError());
    }

    const requestRevision = ++loadRevision;
    loading.value = true;
    error.value = null;
    const request = (async () => {
      try {
        const result = await tradeApi(access.accessToken).cartItems();
        requireCurrent(access);
        if (requestRevision !== loadRevision) {
          throw new AccountCartAccessChangedError();
        }
        items.value = result;
        if (mutationStatus.value === "unknown") {
          mutationStatus.value = "idle";
          mutationMessage.value = null;
        }
      } catch (cause) {
        if (!accessIsCurrent(access) || requestRevision !== loadRevision) {
          throw new AccountCartAccessChangedError();
        }
        error.value = cause instanceof Error ? cause.message : "账户购物车暂时无法读取。";
        throw cause;
      } finally {
        if (accessIsCurrent(access) && requestRevision === loadRevision) {
          loading.value = false;
          activeLoadPromise = null;
          activeLoadAccessRevision = -1;
        }
      }
    })();
    activeLoadPromise = request;
    activeLoadAccessRevision = access.revision;
    return request;
  }

  async function updateItem(
    context: AccountCartAccessContext,
    item: CartItem,
    input: Pick<PutCartItemInput, "quantity" | "selected">,
  ): Promise<CartItem> {
    const quantity = Math.trunc(input.quantity);
    if (!Number.isFinite(quantity) || quantity < 1 || quantity > 1_000_000_000) {
      throw new Error("购物车数量必须是 1 到 1000000000 之间的整数。");
    }
    return mutate(
      context,
      item.skuId,
      async (api) => {
        const updated = await api.putCartItem(item.skuId, {
          productId: item.productId,
          quantity,
          selected: input.selected,
        });
        if (updated.skuId !== item.skuId || updated.productId !== item.productId) {
          throw new AccountCartResponseMismatchError();
        }
        const index = items.value.findIndex((candidate) =>
          candidate.skuId === item.skuId);
        if (index < 0) {
          items.value.push(updated);
        } else {
          items.value[index] = updated;
        }
        return updated;
      },
      "购物车修改已由 Trade 确认。",
    );
  }

  async function removeItem(
    context: AccountCartAccessContext,
    item: CartItem,
  ): Promise<void> {
    await mutate(
      context,
      item.skuId,
      async (api) => {
        await api.removeCartItem(item.skuId);
        items.value = items.value.filter((candidate) => candidate.skuId !== item.skuId);
      },
      "商品已从账户购物车移出。",
    );
  }

  async function mutate<Result>(
    context: AccountCartAccessContext,
    skuId: BusinessId,
    operation: (api: TradeApi) => Promise<Result>,
    successMessage: string,
  ): Promise<Result> {
    const access = synchronizeAccess(context);
    if (!access) {
      const cause = new Error("当前会话没有可用于管理购物车的账户事实。");
      error.value = cause.message;
      throw cause;
    }
    if (mutatingSkuId.value) {
      throw new AccountCartMutationBusyError();
    }

    loadRevision += 1;
    activeLoadPromise = null;
    activeLoadAccessRevision = -1;
    loading.value = false;
    const requestRevision = ++mutationRevision;
    mutatingSkuId.value = skuId;
    error.value = null;
    mutationStatus.value = "pending";
    mutationMessage.value = "正在等待 Trade 确认购物车事实。";
    try {
      const result = await operation(tradeApi(access.accessToken));
      requireCurrent(access);
      if (requestRevision !== mutationRevision) {
        throw new AccountCartAccessChangedError();
      }
      mutationStatus.value = "succeeded";
      mutationMessage.value = successMessage;
      return result;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== mutationRevision) {
        throw new AccountCartAccessChangedError();
      }
      if (resultMayBeUnknown(cause)) {
        mutationStatus.value = "unknown";
        mutationMessage.value = "购物车修改结果尚未确认。请先重新读取 Trade 事实，不要连续重复操作。";
      } else {
        mutationStatus.value = "failed";
        mutationMessage.value = cause instanceof Error
          ? cause.message
          : "购物车修改未完成。";
      }
      throw cause;
    } finally {
      if (accessIsCurrent(access) && requestRevision === mutationRevision) {
        mutatingSkuId.value = null;
      }
    }
  }

  return {
    items,
    loading,
    mutating,
    mutatingSkuId,
    error,
    mutationStatus,
    mutationMessage,
    activeOwnerId,
    selectedItems,
    itemCount,
    selectedItemCount,
    selectedSubtotal,
    load,
    updateItem,
    removeItem,
  };
});
