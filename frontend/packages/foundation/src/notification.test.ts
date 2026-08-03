import { describe, expect, it, vi } from "vitest";

import type { ApiClient, ApiRequestOptions } from "./api";
import { createNotificationApi } from "./notification";

describe("Notification API", () => {
  it("keeps large notification IDs as strings in the read command path", async () => {
    const request = vi.fn();
    const client: ApiClient = {
      async request<T>(
        path: string,
        options?: ApiRequestOptions,
      ): Promise<T> {
        request(path, options);
        return undefined as T;
      },
    };
    const api = createNotificationApi(client);

    await api.markRead("9223372036854775806");

    expect(request).toHaveBeenCalledWith(
      "/api/v1/notifications/9223372036854775806/read",
      { method: "POST" },
    );
  });

  it("passes the opaque keyset cursor without decoding it in the browser", async () => {
    const request = vi.fn();
    const client: ApiClient = {
      async request<T>(path: string): Promise<T> {
        request(path);
        return {
          items: [],
          nextCursor: null,
          hasMore: false,
        } as T;
      },
    };
    const api = createNotificationApi(client);

    await api.notifications("opaque cursor+/=", 40);

    expect(request).toHaveBeenCalledWith(
      "/api/v1/notifications?size=40&cursor=opaque+cursor%2B%2F%3D",
    );
  });

  it("saves an explicit email preference without inventing a read model", async () => {
    const request = vi.fn();
    const client: ApiClient = {
      async request<T>(
        path: string,
        options?: ApiRequestOptions,
      ): Promise<T> {
        request(path, options);
        return {
          userId: "2079000000000000999",
          email: "reader@example.com",
          enabled: true,
          updatedAt: "2026-08-03T00:00:00Z",
        } as T;
      },
    };
    const api = createNotificationApi(client);

    await api.saveEmailPreference("reader@example.com", true);

    expect(request).toHaveBeenCalledWith(
      "/api/v1/notifications/email-preference",
      {
        method: "PUT",
        body: JSON.stringify({
          email: "reader@example.com",
          enabled: true,
        }),
      },
    );
  });
});
