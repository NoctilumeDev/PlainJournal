import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createNotificationApi,
  type BusinessId,
  type InAppNotification,
  type NotificationApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface NotificationAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveNotificationAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
  api: NotificationApi;
}

export type NotificationReadRecovery =
  | "confirmed-read"
  | "still-unread"
  | "missing"
  | "unavailable";

export class NotificationAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的通知请求结果不会写入当前页面。");
    this.name = "NotificationAccessChangedError";
  }
}

export class NotificationContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotificationContractError";
  }
}

function isActiveContext(context: NotificationAccessContext): context is {
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

function notificationApi(accessToken: string): NotificationApi {
  return createNotificationApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function validateNotification(
  value: InAppNotification,
): InAppNotification {
  if (typeof value.id !== "string" || value.id.length === 0) {
    throw new NotificationContractError(
      "通知服务返回了非字符串业务 ID，页面已拒绝执行已读命令。",
    );
  }
  if (!["UNREAD", "READ"].includes(value.status)) {
    throw new NotificationContractError(
      `通知服务返回了未识别状态 ${value.status}，页面不会猜测其已读含义。`,
    );
  }
  return value;
}

function resultMayBeUnknown(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

export const useNotificationStore = defineStore("customer-notifications", () => {
  const notifications = ref<InAppNotification[]>([]);
  const unreadCount = ref(0);
  const nextCursor = ref<string | null>(null);
  const hasMore = ref(false);
  const loading = ref(false);
  const loadingOlder = ref(false);
  const markingReadId = ref<BusinessId | null>(null);
  const error = ref<string | null>(null);
  const readError = ref<string | null>(null);
  const readUnknown = ref(false);
  const pendingReadId = ref<BusinessId | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let loadRevision = 0;
  let factRevision = 0;

  const visibleUnreadCount = computed(() =>
    notifications.value.filter((item) => item.status === "UNREAD").length);

  function synchronizeAccess(
    context: NotificationAccessContext,
  ): ActiveNotificationAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const accessChanged = ownerChanged || activeAccessToken !== nextAccessToken;

    if (accessChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      loadRevision += 1;
      loading.value = false;
      loadingOlder.value = false;
      markingReadId.value = null;
      error.value = null;
      readError.value = null;
      readUnknown.value = false;
      pendingReadId.value = null;
      if (ownerChanged) {
        notifications.value = [];
        unreadCount.value = 0;
        nextCursor.value = null;
        hasMore.value = false;
      }
    }

    if (!isActiveContext(context)) {
      notifications.value = [];
      unreadCount.value = 0;
      nextCursor.value = null;
      hasMore.value = false;
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
      api: notificationApi(context.accessToken),
    };
  }

  function accessIsCurrent(access: ActiveNotificationAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveNotificationAccess) {
    if (!accessIsCurrent(access)) {
      throw new NotificationAccessChangedError();
    }
  }

  function mergeItems(
    incoming: InAppNotification[],
    append: boolean,
  ) {
    const values = append ? [...notifications.value, ...incoming] : incoming;
    const byId = new Map<BusinessId, InAppNotification>();
    for (const item of values) {
      byId.set(item.id, item);
    }
    notifications.value = [...byId.values()].sort((left, right) =>
      Date.parse(right.createdAt) - Date.parse(left.createdAt));
  }

  async function load(
    context: NotificationAccessContext,
    options: { append?: boolean } = {},
  ): Promise<InAppNotification[]> {
    const access = synchronizeAccess(context);
    if (!access) {
      return [];
    }
    const append = Boolean(options.append);
    if (append && (!hasMore.value || !nextCursor.value || loadingOlder.value)) {
      return notifications.value;
    }

    const requestRevision = ++loadRevision;
    const requestedFactRevision = factRevision;
    if (append) {
      loadingOlder.value = true;
    } else {
      loading.value = true;
    }
    error.value = null;
    try {
      const [page, count] = await Promise.all([
        access.api.notifications(append ? nextCursor.value ?? undefined : undefined, 20),
        access.api.unreadCount(),
      ]);
      requireCurrent(access);
      if (
        requestRevision !== loadRevision
        || requestedFactRevision !== factRevision
      ) {
        throw new NotificationAccessChangedError();
      }
      const incoming = page.items.map(validateNotification);
      mergeItems(incoming, append);
      unreadCount.value = Math.max(0, Number(count.count) || 0);
      nextCursor.value = page.nextCursor;
      hasMore.value = page.hasMore;
      return notifications.value;
    } catch (cause) {
      if (
        !accessIsCurrent(access)
        || requestRevision !== loadRevision
        || requestedFactRevision !== factRevision
      ) {
        throw new NotificationAccessChangedError();
      }
      error.value = cause instanceof Error
        ? cause.message
        : "通知事实暂时无法读取。";
      return notifications.value;
    } finally {
      if (accessIsCurrent(access) && requestRevision === loadRevision) {
        if (append) {
          loadingOlder.value = false;
        } else {
          loading.value = false;
        }
      }
    }
  }

  async function markRead(
    context: NotificationAccessContext,
    notificationId: BusinessId,
  ): Promise<boolean> {
    const access = synchronizeAccess(context);
    if (!access) {
      return false;
    }
    const current = notifications.value.find((item) => item.id === notificationId);
    if (!current || current.status === "READ") {
      return Boolean(current);
    }

    markingReadId.value = notificationId;
    readError.value = null;
    readUnknown.value = false;
    pendingReadId.value = null;
    try {
      await access.api.markRead(notificationId);
      requireCurrent(access);
      factRevision += 1;
      notifications.value = notifications.value.map((item) =>
        item.id === notificationId
          ? { ...item, status: "READ", readAt: new Date().toISOString() }
          : item);
      unreadCount.value = Math.max(0, unreadCount.value - 1);
      return true;
    } catch (cause) {
      requireCurrent(access);
      if (resultMayBeUnknown(cause)) {
        readUnknown.value = true;
        pendingReadId.value = notificationId;
        readError.value = "已读结果尚未确认。请先重新读取通知，不要把响应丢失当作未执行。";
        return false;
      }
      readError.value = cause instanceof Error ? cause.message : "通知未能标记为已读。";
      return false;
    } finally {
      if (accessIsCurrent(access) && markingReadId.value === notificationId) {
        markingReadId.value = null;
      }
    }
  }

  async function reconcilePendingRead(
    context: NotificationAccessContext,
  ): Promise<NotificationReadRecovery> {
    const notificationId = pendingReadId.value;
    if (!notificationId) {
      await load(context);
      return error.value ? "unavailable" : "missing";
    }
    await load(context);
    if (error.value) {
      readUnknown.value = true;
      readError.value = "权威通知列表仍无法读取，已读结果继续保持待确认。";
      return "unavailable";
    }
    const current = notifications.value.find((item) => item.id === notificationId);
    readUnknown.value = false;
    pendingReadId.value = null;
    if (!current) {
      readError.value = "权威通知列表中已找不到这条通知，请重新核对账户与通知入口。";
      return "missing";
    }
    if (current.status === "READ") {
      readError.value = null;
      return "confirmed-read";
    }
    readError.value = "权威通知列表仍显示未读；现在可以再次提交幂等的已读命令。";
    return "still-unread";
  }

  return {
    notifications,
    unreadCount,
    visibleUnreadCount,
    nextCursor,
    hasMore,
    loading,
    loadingOlder,
    markingReadId,
    error,
    readError,
    readUnknown,
    pendingReadId,
    activeOwnerId,
    load,
    markRead,
    reconcilePendingRead,
  };
});
