import { createApiClient, createCatalogApi } from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export const catalogApi = createCatalogApi(createApiClient({
  baseUrl: apiBaseUrl,
  timeoutMs: 8000,
}));
