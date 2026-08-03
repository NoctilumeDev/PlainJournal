import type {
  ApiClient,
  BusinessId,
  CursorPageResponse,
} from "./api";

export interface InAppNotification {
  id: BusinessId;
  templateCode: string;
  referenceType: string;
  referenceNo: string;
  title: string;
  content: string;
  status: string;
  readAt: string | null;
  createdAt: string;
}

export interface NotificationUnreadCount {
  count: number;
}

export interface EmailPreference {
  userId: BusinessId;
  email: string | null;
  enabled: boolean;
  updatedAt: string;
}

export interface NotificationApi {
  notifications(
    cursor?: string,
    size?: number,
  ): Promise<CursorPageResponse<InAppNotification>>;
  unreadCount(): Promise<NotificationUnreadCount>;
  markRead(notificationId: BusinessId): Promise<void>;
  saveEmailPreference(
    email: string | null,
    enabled: boolean,
  ): Promise<EmailPreference>;
}

export function createNotificationApi(client: ApiClient): NotificationApi {
  return {
    notifications(cursor, size = 20) {
      const query = new URLSearchParams({ size: String(size) });
      if (cursor) {
        query.set("cursor", cursor);
      }
      return client.request<CursorPageResponse<InAppNotification>>(
        `/api/v1/notifications?${query}`,
      );
    },
    unreadCount() {
      return client.request<NotificationUnreadCount>(
        "/api/v1/notifications/unread-count",
      );
    },
    markRead(notificationId) {
      return client.request<void>(
        `/api/v1/notifications/${encodeURIComponent(notificationId)}/read`,
        { method: "POST" },
      );
    },
    saveEmailPreference(email, enabled) {
      return client.request<EmailPreference>(
        "/api/v1/notifications/email-preference",
        {
          method: "PUT",
          body: JSON.stringify({ email, enabled }),
        },
      );
    },
  };
}
