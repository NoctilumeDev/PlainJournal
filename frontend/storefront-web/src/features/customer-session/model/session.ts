import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createBrowserSessionRelay,
  createApiClient,
  createIdentityApi,
  createTradeApi,
  secureRandomUUID,
  SessionCoordinationUnavailableError,
  withBrowserSessionLock,
  type AuthTokens,
  type LoginInput,
  type RegisterInput,
  type UnauthorizedRequestContext,
  type UserProfile,
} from "@plain-journal/foundation";

import {
  GuestBagMergeOwnershipError,
  useBagStore,
} from "../../../entities/guest-bag";
import {
  configureAuthenticatedSession,
  createAuthenticatedApiClient,
} from "../../../shared/api";

const LEGACY_REFRESH_TOKEN_KEY = "plain-journal:customer-refresh-token:v1";
const SESSION_RECORD_KEY = "plain-journal:customer-session:v2";
const SESSION_LOCK_NAME = "plain-journal:customer-session";
const SESSION_RELAY_NAME = "plain-journal:customer-session:rotation";
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

interface PersistedSession {
  authority: string;
  refreshToken: string;
}

interface SessionRotationMessage {
  type: "rotation-completed";
  authority: string;
  previousRefreshToken: string;
  tokens: AuthTokens;
  profile: UserProfile;
}

export type SessionRecoveryState =
  | "idle"
  | "refreshing"
  | "outcome-unknown"
  | "reauth-required";

export type BagMergeStatus =
  | "idle"
  | "pending"
  | "succeeded"
  | "unknown"
  | "failed"
  | "ownership-conflict";

function readPersistedSession(): PersistedSession | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  const raw = localStorage.getItem(SESSION_RECORD_KEY);
  if (!raw) {
    return null;
  }
  try {
    const value = JSON.parse(raw) as Partial<PersistedSession>;
    return typeof value.authority === "string"
      && value.authority.length > 0
      && typeof value.refreshToken === "string"
      && value.refreshToken.length > 0
      ? { authority: value.authority, refreshToken: value.refreshToken }
      : null;
  } catch {
    return null;
  }
}

function readLegacyRefreshToken(): string | null {
  return typeof localStorage === "undefined"
    ? null
    : localStorage.getItem(LEGACY_REFRESH_TOKEN_KEY);
}

function writePersistedSession(session: PersistedSession): void {
  if (typeof localStorage === "undefined") {
    return;
  }
  localStorage.setItem(SESSION_RECORD_KEY, JSON.stringify(session));
  localStorage.setItem(LEGACY_REFRESH_TOKEN_KEY, session.refreshToken);
}

function removePersistedSession(expected: PersistedSession): boolean {
  if (typeof localStorage === "undefined") {
    return true;
  }
  const current = readPersistedSession();
  if (current) {
    if (
      current.authority !== expected.authority
      || current.refreshToken !== expected.refreshToken
    ) {
      return false;
    }
    localStorage.removeItem(SESSION_RECORD_KEY);
  } else if (localStorage.getItem(LEGACY_REFRESH_TOKEN_KEY) !== expected.refreshToken) {
    return false;
  }
  if (localStorage.getItem(LEGACY_REFRESH_TOKEN_KEY) === expected.refreshToken) {
    localStorage.removeItem(LEGACY_REFRESH_TOKEN_KEY);
  }
  return true;
}

function isRefreshOutcomeUnknown(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

function refreshOutcomeUnknown(cause: unknown): ApiError {
  const error = cause instanceof ApiError ? cause : undefined;
  return new ApiError(
    error?.kind ?? "network",
    "REFRESH_OUTCOME_UNKNOWN",
    "会话刷新结果未知。为避免猜测凭据是否已旋转，请重新登录。",
    error?.status,
    { cause },
  );
}

export const useSessionStore = defineStore("customer-session", () => {
  const initial = readPersistedSession();
  const profile = ref<UserProfile | null>(null);
  const accessToken = ref<string | null>(null);
  const refreshToken = ref<string | null>(
    initial?.refreshToken ?? readLegacyRefreshToken(),
  );
  const sessionAuthority = ref<string | null>(initial?.authority ?? null);
  const initialized = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const logoutError = ref<string | null>(null);
  const recoveryState = ref<SessionRecoveryState>("idle");
  const recoveryMessage = ref<string | null>(null);
  const bagMergeStatus = ref<BagMergeStatus>("idle");
  const bagMergeMessage = ref<string | null>(null);
  let restorePromise: Promise<void> | null = null;
  let mergePromise: Promise<void> | null = null;
  let mergeOwnerId: string | null = null;
  let mergeRevision = 0;
  const rotationRelay = createBrowserSessionRelay<SessionRotationMessage>(
    SESSION_RELAY_NAME,
    installPeerRotation,
  );

  const authControlApi = createIdentityApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
  }));
  const identityReadApi = createIdentityApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken.value,
  }));
  const tradeApi = createTradeApi(createAuthenticatedApiClient(null));

  configureAuthenticatedSession({
    accessToken: () => accessToken.value,
    requestAuthority: () => sessionAuthority.value ?? accessToken.value,
    onUnauthorized: handleUnauthorized,
  });

  const authenticated = computed(() => Boolean(profile.value && accessToken.value));
  const requestAuthority = computed(() => sessionAuthority.value ?? accessToken.value);
  const reauthRequired = computed(() => recoveryState.value === "reauth-required"
    || recoveryState.value === "outcome-unknown");

  function setLocalSession(tokens: AuthTokens, authority: string): void {
    accessToken.value = tokens.accessToken;
    refreshToken.value = tokens.refreshToken;
    sessionAuthority.value = authority;
  }

  function clearLocalSession(): void {
    profile.value = null;
    accessToken.value = null;
    refreshToken.value = null;
    sessionAuthority.value = null;
  }

  function requireReauthentication(
    message: string,
    state: Extract<SessionRecoveryState, "outcome-unknown" | "reauth-required">,
  ): void {
    profile.value = null;
    accessToken.value = null;
    recoveryState.value = state;
    recoveryMessage.value = message;
    error.value = message;
  }

  function ensurePersistedSession(): PersistedSession | null {
    const current = readPersistedSession();
    if (current) {
      return current;
    }
    const legacy = readLegacyRefreshToken();
    if (!legacy) {
      return null;
    }
    const migrated = {
      authority: secureRandomUUID(),
      refreshToken: legacy,
    };
    writePersistedSession(migrated);
    return migrated;
  }

  function installPeerRotation(message: SessionRotationMessage): boolean {
    const current = readPersistedSession();
    if (
      message.type !== "rotation-completed"
      || !current
      || current.authority !== message.authority
      || current.refreshToken !== message.tokens.refreshToken
      || sessionAuthority.value !== message.authority
      || (
        refreshToken.value !== message.previousRefreshToken
        && refreshToken.value !== message.tokens.refreshToken
      )
    ) {
      return false;
    }
    setLocalSession(message.tokens, message.authority);
    profile.value = message.profile;
    recoveryState.value = "idle";
    recoveryMessage.value = null;
    error.value = null;
    return true;
  }

  async function installPeerRotationIfAvailable(
    persisted: PersistedSession,
  ): Promise<boolean> {
    const previousRefreshToken = refreshToken.value;
    if (
      !previousRefreshToken
      || previousRefreshToken === persisted.refreshToken
      || !rotationRelay.available
    ) {
      return false;
    }
    const message = await rotationRelay.waitFor((candidate) =>
      candidate.type === "rotation-completed"
      && candidate.authority === persisted.authority
      && candidate.previousRefreshToken === previousRefreshToken
      && candidate.tokens.refreshToken === persisted.refreshToken);
    return message ? installPeerRotation(message) : false;
  }

  async function refreshWithOneUnknownRetry(token: string): Promise<AuthTokens> {
    try {
      return await authControlApi.refresh(token);
    } catch (firstCause) {
      if (!isRefreshOutcomeUnknown(firstCause)) {
        throw firstCause;
      }
      try {
        return await authControlApi.refresh(token);
      } catch (secondCause) {
        if (isRefreshOutcomeUnknown(secondCause)) {
          throw refreshOutcomeUnknown(secondCause);
        }
        throw secondCause;
      }
    }
  }

  async function installAndVerify(
    tokens: AuthTokens,
    authority: string,
  ): Promise<UserProfile> {
    const next = { authority, refreshToken: tokens.refreshToken };
    writePersistedSession(next);
    setLocalSession(tokens, authority);
    const candidate = await identityReadApi.currentUser();
    profile.value = candidate;
    recoveryState.value = "idle";
    recoveryMessage.value = null;
    error.value = null;
    return candidate;
  }

  async function handleUnauthorized(
    context: UnauthorizedRequestContext,
  ): Promise<"retry" | "reject"> {
    if (
      context.requestAuthority
      && context.requestAuthority !== sessionAuthority.value
    ) {
      return "reject";
    }
    if (context.retryAttempted) {
      requireReauthentication(
        "刷新后的请求仍未通过身份校验，请重新登录。",
        "reauth-required",
      );
      return "reject";
    }

    try {
      return await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
        if (
          context.requestAuthority
          && context.requestAuthority !== sessionAuthority.value
        ) {
          return "reject";
        }
        if (
          accessToken.value
          && context.accessToken
          && accessToken.value !== context.accessToken
          && profile.value
        ) {
          return "retry";
        }
        const expectedAuthority = sessionAuthority.value;
        const current = readPersistedSession();
        if (!expectedAuthority || !current || current.authority !== expectedAuthority) {
          requireReauthentication(
            "当前页面已失去对最新设备会话的所有权，请重新登录。",
            "reauth-required",
          );
          return "reject";
        }
        if (await installPeerRotationIfAvailable(current)) {
          return "retry";
        }

        recoveryState.value = "refreshing";
        recoveryMessage.value = "访问凭据已过期，正在确认会话连续性。";
        try {
          const tokens = await refreshWithOneUnknownRetry(current.refreshToken);
          const verifiedProfile = await installAndVerify(tokens, current.authority);
          rotationRelay.publish({
            type: "rotation-completed",
            authority: current.authority,
            previousRefreshToken: current.refreshToken,
            tokens,
            profile: verifiedProfile,
          });
          return "retry";
        } catch (cause) {
          if (cause instanceof ApiError && cause.status === 401) {
            removePersistedSession(current);
            refreshToken.value = null;
            sessionAuthority.value = null;
            requireReauthentication(
              "刷新凭据已失效，当前客户端无法继续证明会话连续性。",
              "reauth-required",
            );
            return "reject";
          }
          if (isRefreshOutcomeUnknown(cause)) {
            requireReauthentication(
              "刷新请求结果未知；系统不会猜测服务端是否已经完成令牌旋转。",
              "outcome-unknown",
            );
            throw refreshOutcomeUnknown(cause);
          }
          requireReauthentication(
            cause instanceof Error ? cause.message : "会话连续性无法确认，请重新登录。",
            "reauth-required",
          );
          return "reject";
        }
      });
    } catch (cause) {
      if (cause instanceof SessionCoordinationUnavailableError) {
        requireReauthentication(cause.message, "reauth-required");
        return "reject";
      }
      throw cause;
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

  function mergeIsCurrent(ownerId: string, authority: string, revision: number): boolean {
    return mergeRevision === revision
      && profile.value?.id === ownerId
      && requestAuthority.value === authority;
  }

  async function performGuestBagMerge(
    ownerId: string,
    authority: string,
    revision: number,
  ) {
    const bag = useBagStore();
    let pending;
    try {
      pending = bag.prepareMerge(ownerId);
    } catch (cause) {
      if (!mergeIsCurrent(ownerId, authority, revision)) {
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
      if (mergeIsCurrent(ownerId, authority, revision)) {
        bagMergeStatus.value = "idle";
        bagMergeMessage.value = null;
      }
      return;
    }

    bagMergeStatus.value = "pending";
    bagMergeMessage.value = "正在把当前设备的购物袋安全合并到账户。";
    try {
      await tradeApi.mergeGuestBag(pending.items, pending.key);
      if (!mergeIsCurrent(ownerId, authority, revision)) {
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
      if (!mergeIsCurrent(ownerId, authority, revision)) {
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
    const authority = requestAuthority.value;
    if (!ownerId || !authority) {
      return Promise.resolve();
    }
    if (mergePromise && mergeOwnerId === ownerId) {
      return mergePromise;
    }

    const revision = ++mergeRevision;
    const request = performGuestBagMerge(ownerId, authority, revision);
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

  async function establishNewSession(tokens: AuthTokens): Promise<void> {
    await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
      await installAndVerify(tokens, secureRandomUUID());
    });
    await mergeGuestBag();
  }

  async function login(input: LoginInput) {
    busy.value = true;
    error.value = null;
    logoutError.value = null;
    try {
      await establishNewSession(await authControlApi.login(input));
      initialized.value = true;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "登录未完成。";
      throw cause;
    } finally {
      busy.value = false;
    }
  }

  async function registerAndLogin(input: RegisterInput) {
    busy.value = true;
    error.value = null;
    try {
      await authControlApi.register(input);
      await establishNewSession(await authControlApi.login({
        email: input.email,
        password: input.password,
      }));
      initialized.value = true;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "注册未完成。";
      throw cause;
    } finally {
      busy.value = false;
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
      busy.value = true;
      error.value = null;
      let restored = false;
      try {
        await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
          const stored = ensurePersistedSession();
          if (!stored) {
            initialized.value = true;
            return;
          }
          sessionAuthority.value = stored.authority;
          refreshToken.value = stored.refreshToken;
          recoveryState.value = "refreshing";
          const tokens = await refreshWithOneUnknownRetry(stored.refreshToken);
          await installAndVerify(tokens, stored.authority);
          restored = true;
        });
        if (restored) {
          await mergeGuestBag();
          initialized.value = true;
        }
      } catch (cause) {
        const current = sessionAuthority.value && refreshToken.value
          ? { authority: sessionAuthority.value, refreshToken: refreshToken.value }
          : null;
        if (cause instanceof ApiError && cause.status === 401) {
          if (current) {
            await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
              removePersistedSession(current);
            });
          }
          clearLocalSession();
          recoveryState.value = "idle";
          recoveryMessage.value = null;
          error.value = null;
          initialized.value = true;
          return;
        }
        if (isRefreshOutcomeUnknown(cause)) {
          requireReauthentication(
            "会话恢复结果未知；刷新凭据已保留，但本次不会假定旋转成功或失败。",
            "outcome-unknown",
          );
        } else {
          requireReauthentication(
            cause instanceof Error ? cause.message : "暂时无法恢复会话。",
            "reauth-required",
          );
        }
        initialized.value = false;
      } finally {
        busy.value = false;
      }
    })().finally(() => {
      restorePromise = null;
    });
    return restorePromise;
  }

  async function logout() {
    logoutError.value = null;
    const expectedAuthority = sessionAuthority.value;
    if (!expectedAuthority) {
      clearLocalSession();
      return;
    }
    busy.value = true;
    try {
      await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
        const current = readPersistedSession();
        if (!current || current.authority !== expectedAuthority) {
          clearLocalSession();
          return;
        }
        await authControlApi.logout(current.refreshToken);
        removePersistedSession(current);
        clearLocalSession();
        recoveryState.value = "idle";
        recoveryMessage.value = null;
      });
    } catch (cause) {
      logoutError.value = cause instanceof Error
        ? cause.message
        : "服务端退出结果未知，当前设备仍保留会话。";
      throw cause;
    } finally {
      busy.value = false;
    }
  }

  async function clearLocalOnly() {
    const expectedAuthority = sessionAuthority.value;
    const expectedRefresh = refreshToken.value;
    await withBrowserSessionLock(SESSION_LOCK_NAME, async () => {
      if (expectedAuthority && expectedRefresh) {
        removePersistedSession({
          authority: expectedAuthority,
          refreshToken: expectedRefresh,
        });
      } else if (typeof localStorage !== "undefined" && !readPersistedSession()) {
        localStorage.removeItem(LEGACY_REFRESH_TOKEN_KEY);
      }
      clearLocalSession();
      recoveryState.value = "idle";
      recoveryMessage.value = null;
      logoutError.value = null;
    });
  }

  return {
    profile,
    accessToken,
    requestAuthority,
    initialized,
    busy,
    error,
    logoutError,
    recoveryState,
    recoveryMessage,
    reauthRequired,
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
