export type BusinessId = string;

export interface ApiEnvelope<T> {
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface CursorPageResponse<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

export type ApiErrorKind =
  | "network"
  | "timeout"
  | "http"
  | "business"
  | "invalid-response";

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly code: string;
  readonly status: number | undefined;

  constructor(
    kind: ApiErrorKind,
    code: string,
    message: string,
    status?: number,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "ApiError";
    this.kind = kind;
    this.code = code;
    this.status = status;
  }
}

export interface ApiClientOptions {
  baseUrl?: string;
  timeoutMs?: number;
  tokenProvider?: () => string | null;
}

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
}

export interface ApiClient {
  request<T>(path: string, options?: ApiRequestOptions): Promise<T>;
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return Boolean(
    value
    && typeof value === "object"
    && "code" in value
    && "message" in value
    && "timestamp" in value,
  );
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const baseUrl = options.baseUrl?.replace(/\/$/, "") ?? "";
  const defaultTimeoutMs = options.timeoutMs ?? 8000;

  return {
    async request<T>(path: string, requestOptions: ApiRequestOptions = {}): Promise<T> {
      const controller = new AbortController();
      const timeoutMs = requestOptions.timeoutMs ?? defaultTimeoutMs;
      const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs);
      const headers = new Headers(requestOptions.headers);
      headers.set("Accept", "application/json");
      if (requestOptions.body && !headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }
      const token = options.tokenProvider?.();
      if (token) {
        headers.set("Authorization", `Bearer ${token}`);
      }

      try {
        const response = await fetch(`${baseUrl}${path}`, {
          ...requestOptions,
          headers,
          signal: controller.signal,
        });
        const payload: unknown = await response.json().catch((cause: unknown) => {
          throw new ApiError(
            "invalid-response",
            "INVALID_RESPONSE",
            "服务返回了无法识别的响应。",
            response.status,
            { cause },
          );
        });
        if (!isEnvelope(payload)) {
          throw new ApiError(
            "invalid-response",
            "INVALID_RESPONSE",
            "服务响应缺少统一结果结构。",
            response.status,
          );
        }
        if (!response.ok) {
          throw new ApiError(
            "http",
            payload.code || `HTTP_${response.status}`,
            payload.message || "请求未完成。",
            response.status,
          );
        }
        if (payload.code !== "OK") {
          throw new ApiError(
            "business",
            payload.code,
            payload.message || "业务状态不允许执行当前操作。",
            response.status,
          );
        }
        return payload.data as T;
      } catch (error) {
        if (error instanceof ApiError) {
          throw error;
        }
        if (error instanceof DOMException && error.name === "AbortError") {
          throw new ApiError(
            "timeout",
            "REQUEST_TIMEOUT",
            "请求等待时间过长，请稍后重试。",
            undefined,
            { cause: error },
          );
        }
        throw new ApiError(
          "network",
          "NETWORK_UNAVAILABLE",
          "暂时无法连接服务。你的操作没有被标记为成功。",
          undefined,
          { cause: error },
        );
      } finally {
        globalThis.clearTimeout(timeout);
      }
    },
  };
}
