import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createIdentityApi,
  createTradeApi,
  type AuthTokens,
  type LoginInput,
  type RegisterInput,
  type UserProfile,
} from "@plain-journal/foundation";

import {
  GuestBagMergeOwnershipError,
  useBagStore,
} from "../../../entities/guest-bag";

const REFRESH_TOKEN_KEY = "plain-journal:customer-refresh-token:v1";
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export type BagMergeStatus =
  | "idle"
  | "pending"
  | "succeeded"
  | "unknown"
  | "failed"
  | "ownership-conflict";

function storedRefreshToken(): string | null {
  return typeof localStorage === "undefined"
    ? null
    : localStorage.getItem(REFRESH_TOKEN_KEY);
}

export const useSessionStore = defineStore("customer-session", () => {
  const profile = ref<UserProfile | null>(null);
  const accessToken = ref<string | null>(null);
  const refreshToken = ref<string | null>(storedRefreshToken());
  const initialized = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const logoutError = ref<string | null>(null);
  const bagMergeStatus = ref<BagMergeStatus>("idle");
  const bagMergeMessage = ref<string | null>(null);
  let restorePromise: Promise<void> | null = null;
  let mergePromise: Promise<void> | null = null;
  let mergeOwnerId: string | null = null;
  let mergeRevision = 0;

  const client = createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken.value,
  });
  const identityApi = createIdentityApi(client);
  const tradeApi = createTradeApi(client);

  const authenticated = computed(() => Boolean(profile.value && accessToken.value));

  function persistTokens(tokens: AuthTokens) {
    accessToken.value = tokens.accessToken;
    refreshToken.value = tokens.refreshToken;
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
    }
  }

  function clearSession() {
    profile.value = null;
    accessToken.value = null;
    refreshToken.value = null;
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(REFRESH_TOKEN_KEY);
    }
  }

  function mergeResultMayBeUnknown(cause: unknown): boolean {
    return cause instanceof ApiError && (
      cause.kind === "network"
      || cause.kind === "timeout"
      || cause.kind === "invalid-response"
      || (cause.kind === "http" && (cause.status ?? 500) >= 500)
    );
  }

  function mergeIsCurrent(ownerId: string, token: string, revision: number): boolean {
    return mergeRevision === revision
      && profile.value?.id === ownerId
      && accessToken.value === token;
  }

  async function performGuestBagMerge(
    ownerId: string,
    token: string,
    revision: number,
  ) {
    const bag = useBagStore();
    let pending;
    try {
      pending = bag.prepareMerge(ownerId);
    } catch (cause) {
      if (!mergeIsCurrent(ownerId, token, revision)) {
        return;
      }
      if (cause instanceof GuestBagMergeOwnershipError) {
        bagMergeStatus.value = "ownership-conflict";
        bagMergeMessage.value = cause.message;
        return;
      }
      throw cause;
    }
    if (!pending) {
      if (mergeIsCurrent(ownerId, token, revision)) {
        bagMergeStatus.value = "idle";
        bagMergeMessage.value = null;
      }
      return;
    }

    bagMergeStatus.value = "pending";
    bagMergeMessage.value = "正在把当前设备的购物袋安全合并到账户。";
    try {
      await tradeApi.mergeGuestBag(pending.items, pending.key);
      if (!mergeIsCurrent(ownerId, token, revision)) {
        return;
      }
      if (!bag.completeMerge(pending.key)) {
        bagMergeStatus.value = "unknown";
        bagMergeMessage.value = "Trade 已确认合并，但本地待提交快照已变化，请重新核对账户与设备购物袋。";
        return;
      }
      bagMergeStatus.value = "succeeded";
      bagMergeMessage.value = "设备购物袋已合并到账户，未覆盖原有商品。";
    } catch (cause) {
      if (!mergeIsCurrent(ownerId, token, revision)) {
        return;
      }
      if (mergeResultMayBeUnknown(cause)) {
        bagMergeStatus.value = "unknown";
        bagMergeMessage.value = "合并结果暂时未知，本地商品与重试键均已保留。";
        return;
      }
      bagMergeStatus.value = "failed";
      bagMergeMessage.value = cause instanceof Error
        ? cause.message
        : "购物袋合并未完成，本地商品仍然保留。";
    }
  }

  function mergeGuestBag(): Promise<void> {
    const ownerId = profile.value?.id;
    const token = accessToken.value;
    if (!ownerId || !token) {
      return Promise.resolve();
    }
    if (mergePromise && mergeOwnerId === ownerId) {
      return mergePromise;
    }

    const revision = ++mergeRevision;
    const request = performGuestBagMerge(ownerId, token, revision);
    mergePromise = request;
    mergeOwnerId = ownerId;
    const clearActiveMerge = () => {
      if (mergePromise === request) {
        mergePromise = null;
        mergeOwnerId = null;
      }
    };
    void request.then(clearActiveMerge, clearActiveMerge);
    return request;
  }

  async function establish(tokens: AuthTokens) {
    persistTokens(tokens);
    profile.value = await identityApi.currentUser();
    await mergeGuestBag();
  }

  async function login(input: LoginInput) {
    busy.value = true;
    error.value = null;
    logoutError.value = null;
    try {
      await establish(await identityApi.login(input));
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "登录未完成。";
      throw cause;
    } finally {
      busy.value = false;
      initialized.value = true;
    }
  }

  async function registerAndLogin(input: RegisterInput) {
    busy.value = true;
    error.value = null;
    try {
      await identityApi.register(input);
      await establish(await identityApi.login({
        email: input.email,
        password: input.password,
      }));
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "注册未完成。";
      throw cause;
    } finally {
      busy.value = false;
      initialized.value = true;
    }
  }

  function restore(): Promise<void> {
    if (initialized.value) {
      return Promise.resolve();
    }
    if (restorePromise) {
      return restorePromise;
    }
    restorePromise = (async () => {
      const stored = refreshToken.value;
      if (!stored) {
        initialized.value = true;
        return;
      }
      busy.value = true;
      error.value = null;
      try {
        await establish(await identityApi.refresh(stored));
      } catch (cause) {
        if (cause instanceof ApiError && cause.status === 401) {
          clearSession();
          error.value = null;
          return;
        }
        error.value = cause instanceof Error ? cause.message : "暂时无法恢复会话。";
      } finally {
        busy.value = false;
        initialized.value = true;
      }
    })().finally(() => {
      restorePromise = null;
    });
    return restorePromise;
  }

  async function logout() {
    logoutError.value = null;
    const token = refreshToken.value;
    if (!token) {
      clearSession();
      return;
    }
    busy.value = true;
    try {
      await identityApi.logout(token);
      clearSession();
    } catch (cause) {
      logoutError.value = cause instanceof Error
        ? cause.message
        : "服务端退出结果未知，当前设备仍保留会话。";
      throw cause;
    } finally {
      busy.value = false;
    }
  }

  function clearLocalOnly() {
    clearSession();
    logoutError.value = null;
  }

  return {
    profile,
    accessToken,
    initialized,
    busy,
    error,
    logoutError,
    bagMergeStatus,
    bagMergeMessage,
    authenticated,
    login,
    registerAndLogin,
    restore,
    logout,
    clearLocalOnly,
    mergeGuestBag,
  };
});
