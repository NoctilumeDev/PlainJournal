import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createBrowserSessionRelay,
  createApiClient,
  createIdentityApi,
  secureRandomUUID,
  SessionCoordinationUnavailableError,
  withBrowserSessionLock,
  type AuthTokens,
  type LoginInput,
  type UnauthorizedRequestContext,
  type UserProfile,
} from "@plain-journal/foundation";

import { configureAuthenticatedSession } from "../shared/api";

const LEGACY_REFRESH_TOKEN_KEY = "plain-journal:staff-refresh-token:v1";
const SESSION_RECORD_KEY = "plain-journal:staff-session:v2";
const SESSION_LOCK_NAME = "plain-journal:staff-session";
const SESSION_RELAY_NAME = "plain-journal:staff-session:rotation";
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const WORKSPACE_ROLES = new Set(["ADMIN", "OPERATOR", "WAREHOUSE"]);

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

export type StaffSessionRecoveryState =
  | "idle"
  | "refreshing"
  | "outcome-unknown"
  | "reauth-required";

export function hasWorkspaceRole(roles: string[]): boolean {
  return roles.some((role) => WORKSPACE_ROLES.has(role));
}

export function hasAnyRole(roles: string[], required: string[]): boolean {
  return required.length === 0 || roles.some((role) => required.includes(role));
}

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
    "员工会话刷新结果未知。为避免猜测凭据是否已旋转，请重新登录。",
    error?.status,
    { cause },
  );
}

export interface AccessDeniedFact {
  email: string;
  roles: string[];
  remoteLogoutConfirmed: boolean;
}

export const useStaffSessionStore = defineStore("staff-session", () => {
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
  const accessDenied = ref<AccessDeniedFact | null>(null);
  const recoveryState = ref<StaffSessionRecoveryState>("idle");
  const recoveryMessage = ref<string | null>(null);
  let restorePromise: Promise<void> | null = null;
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

  configureAuthenticatedSession({
    accessToken: () => accessToken.value,
    requestAuthority: () => sessionAuthority.value ?? accessToken.value,
    onUnauthorized: handleUnauthorized,
  });

  const authenticated = computed(() => Boolean(
    profile.value
    && accessToken.value
    && hasWorkspaceRole(profile.value.roles),
  ));
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
    state: Extract<
      StaffSessionRecoveryState,
      "outcome-unknown" | "reauth-required"
    >,
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
      || !hasWorkspaceRole(message.profile.roles)
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
    accessDenied.value = null;
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

  async function rejectNonStaff(
    candidate: UserProfile,
    persisted: PersistedSession,
  ): Promise<void> {
    let remoteLogoutConfirmed = false;
    try {
      await authControlApi.logout(persisted.refreshToken);
      remoteLogoutConfirmed = true;
    } catch {
      // No privileged local session is retained even when remote revoke is unknown.
    }
    removePersistedSession(persisted);
    clearLocalSession();
    accessDenied.value = {
      email: candidate.email,
      roles: [...candidate.roles],
      remoteLogoutConfirmed,
    };
  }

  async function installAndVerify(
    tokens: AuthTokens,
    authority: string,
  ): Promise<boolean> {
    const next = { authority, refreshToken: tokens.refreshToken };
    writePersistedSession(next);
    setLocalSession(tokens, authority);
    const candidate = await identityReadApi.currentUser();
    if (!hasWorkspaceRole(candidate.roles)) {
      await rejectNonStaff(candidate, next);
      return false;
    }
    profile.value = candidate;
    accessDenied.value = null;
    recoveryState.value = "idle";
    recoveryMessage.value = null;
    error.value = null;
    return true;
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
        "刷新后的员工请求仍未通过身份校验，请重新登录。",
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
            "当前页面已失去对最新员工会话的所有权，请重新登录。",
            "reauth-required",
          );
          return "reject";
        }
        if (await installPeerRotationIfAvailable(current)) {
          return "retry";
        }

        recoveryState.value = "refreshing";
        recoveryMessage.value = "员工访问凭据已过期，正在确认会话连续性与角色。";
        try {
          const tokens = await refreshWithOneUnknownRetry(current.refreshToken);
          if (!await installAndVerify(tokens, current.authority) || !profile.value) {
            return "reject";
          }
          rotationRelay.publish({
            type: "rotation-completed",
            authority: current.authority,
            previousRefreshToken: current.refreshToken,
            tokens,
            profile: {
              ...profile.value,
              roles: [...profile.value.roles],
            },
          });
          return "retry";
        } catch (cause) {
          if (cause instanceof ApiError && cause.status === 401) {
            removePersistedSession(current);
            refreshToken.value = null;
            sessionAuthority.value = null;
            requireReauthentication(
              "员工刷新凭据已失效，当前客户端无法继续证明会话连续性。",
              "reauth-required",
            );
            return "reject";
          }
          if (isRefreshOutcomeUnknown(cause)) {
            requireReauthentication(
              "员工会话刷新结果未知；系统不会猜测服务端是否已经完成令牌旋转。",
              "outcome-unknown",
            );
            throw refreshOutcomeUnknown(cause);
          }
          requireReauthentication(
            cause instanceof Error ? cause.message : "员工会话连续性无法确认，请重新登录。",
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

  async function establishNewSession(tokens: AuthTokens): Promise<boolean> {
    return withBrowserSessionLock(SESSION_LOCK_NAME, () =>
      installAndVerify(tokens, secureRandomUUID()));
  }

  async function login(input: LoginInput): Promise<boolean> {
    busy.value = true;
    error.value = null;
    logoutError.value = null;
    accessDenied.value = null;
    try {
      const accepted = await establishNewSession(await authControlApi.login(input));
      initialized.value = true;
      return accepted;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "员工登录未完成。";
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
          initialized.value = true;
        });
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
            "员工会话恢复结果未知；刷新凭据已保留，但本次不会猜测旋转结果。",
            "outcome-unknown",
          );
        } else {
          requireReauthentication(
            cause instanceof Error ? cause.message : "暂时无法恢复员工会话。",
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
        : "服务端退出结果未知，当前员工会话仍保留。";
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
    accessDenied,
    recoveryState,
    recoveryMessage,
    reauthRequired,
    authenticated,
    login,
    restore,
    logout,
    clearLocalOnly,
  };
});
