import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createIdentityApi,
  type AuthTokens,
  type LoginInput,
  type UserProfile,
} from "@plain-journal/foundation";

const REFRESH_TOKEN_KEY = "plain-journal:staff-refresh-token:v1";
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const WORKSPACE_ROLES = new Set(["ADMIN", "OPERATOR", "WAREHOUSE"]);

export function hasWorkspaceRole(roles: string[]): boolean {
  return roles.some((role) => WORKSPACE_ROLES.has(role));
}

export function hasAnyRole(roles: string[], required: string[]): boolean {
  return required.length === 0 || roles.some((role) => required.includes(role));
}

function storedRefreshToken(): string | null {
  return typeof localStorage === "undefined"
    ? null
    : localStorage.getItem(REFRESH_TOKEN_KEY);
}

export interface AccessDeniedFact {
  email: string;
  roles: string[];
  remoteLogoutConfirmed: boolean;
}

export const useStaffSessionStore = defineStore("staff-session", () => {
  const profile = ref<UserProfile | null>(null);
  const accessToken = ref<string | null>(null);
  const refreshToken = ref<string | null>(storedRefreshToken());
  const initialized = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const logoutError = ref<string | null>(null);
  const accessDenied = ref<AccessDeniedFact | null>(null);
  let restorePromise: Promise<void> | null = null;

  const identityApi = createIdentityApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken.value,
  }));
  const authenticated = computed(() => Boolean(
    profile.value
    && accessToken.value
    && hasWorkspaceRole(profile.value.roles),
  ));

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

  async function rejectNonStaff(candidate: UserProfile, token: string) {
    let remoteLogoutConfirmed = false;
    try {
      await identityApi.logout(token);
      remoteLogoutConfirmed = true;
    } catch {
      // The local admin session is still cleared; no privileged role was granted.
    }
    clearSession();
    accessDenied.value = {
      email: candidate.email,
      roles: [...candidate.roles],
      remoteLogoutConfirmed,
    };
  }

  async function establish(tokens: AuthTokens): Promise<boolean> {
    persistTokens(tokens);
    const candidate = await identityApi.currentUser();
    if (!hasWorkspaceRole(candidate.roles)) {
      await rejectNonStaff(candidate, tokens.refreshToken);
      return false;
    }
    profile.value = candidate;
    accessDenied.value = null;
    return true;
  }

  async function login(input: LoginInput): Promise<boolean> {
    busy.value = true;
    error.value = null;
    logoutError.value = null;
    accessDenied.value = null;
    try {
      return await establish(await identityApi.login(input));
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "员工登录未完成。";
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
        error.value = cause instanceof Error ? cause.message : "暂时无法恢复员工会话。";
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
        : "服务端退出结果未知，当前员工会话仍保留。";
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
    accessDenied,
    authenticated,
    login,
    restore,
    logout,
    clearLocalOnly,
  };
});
