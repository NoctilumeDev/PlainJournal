import {
  createApiClient,
  type ApiClient,
  type UnauthorizedHandler,
} from "@plain-journal/foundation";

export interface AuthenticatedSessionBinding {
  accessToken: () => string | null;
  requestAuthority: () => string | null;
  onUnauthorized: UnauthorizedHandler;
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const allowFallbackAccessToken = import.meta.env.MODE === "test";
let sessionBinding: AuthenticatedSessionBinding | null = null;

export function configureAuthenticatedSession(
  binding: AuthenticatedSessionBinding,
): void {
  sessionBinding = binding;
}

export function createAuthenticatedApiClient(
  fallbackAccessToken: string | null,
  timeoutMs = 8000,
): ApiClient {
  return createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs,
    tokenProvider: () => sessionBinding
      ? sessionBinding.accessToken()
      : allowFallbackAccessToken
        ? fallbackAccessToken
        : null,
    requestAuthorityProvider: () => sessionBinding?.requestAuthority() ?? null,
    onUnauthorized: async (context) => sessionBinding
      ? sessionBinding.onUnauthorized(context)
      : "reject",
  });
}
