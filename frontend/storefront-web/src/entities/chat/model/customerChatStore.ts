import {
  computed,
  reactive,
  shallowRef,
} from "vue";
import { defineStore } from "pinia";

import {
  createChatWorkspaceApi,
  createChatWorkspaceController,
  createChatWorkspaceState,
  type BusinessId,
  type ChatApi,
  type ChatConversation,
  type ChatMessage,
  type ChatMessagePage,
  type ChatWebSocketTicket,
  type ChatWorkspaceController,
  type ChatWorkspaceState,
  type PendingChatConversation,
  type PendingChatSend,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_SEND_KEY = "plain-journal:customer-chat-pending-send:v1";
const PENDING_CONVERSATION_KEY = "plain-journal:customer-chat-pending-conversation:v1";

export interface ChatAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface CustomerChatWorkspace {
  ownerId: BusinessId;
  accessToken: string;
  state: ChatWorkspaceState;
  controller: ChatWorkspaceController;
}

export class ChatAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的 Chat 请求结果不会写入当前页面。");
    this.name = "ChatAccessChangedError";
  }
}

export class ChatOwnershipMismatchError extends Error {
  constructor() {
    super("Chat 响应包含不属于当前账户的会话，页面已拒绝展示。");
    this.name = "ChatOwnershipMismatchError";
  }
}

export class ChatContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ChatContractError";
  }
}

function isActiveContext(context: ChatAccessContext): context is {
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

function validateConversation(
  value: ChatConversation,
  ownerId: BusinessId,
): ChatConversation {
  if (
    typeof value.id !== "string"
    || typeof value.customerId !== "string"
    || value.customerId !== ownerId
  ) {
    throw new ChatOwnershipMismatchError();
  }
  if (!["OPEN", "CLOSED"].includes(value.status)) {
    throw new ChatContractError(
      `Chat 返回了未识别会话状态 ${value.status}，页面不会猜测可写性。`,
    );
  }
  return value;
}

function validateMessage(
  value: ChatMessage,
  conversationId: BusinessId,
): ChatMessage {
  if (
    typeof value.id !== "string"
    || typeof value.conversationId !== "string"
    || value.conversationId !== conversationId
    || typeof value.senderId !== "string"
    || typeof value.clientMessageId !== "string"
  ) {
    throw new ChatContractError("Chat 返回了无法安全归属的消息事实。");
  }
  if (value.attachments.some((attachment) => typeof attachment.id !== "string")) {
    throw new ChatContractError("Chat 附件响应包含非字符串业务 ID，页面已拒绝展示。");
  }
  return value;
}

function validateMessagePage(
  page: ChatMessagePage,
  conversationId: BusinessId,
): ChatMessagePage {
  return {
    ...page,
    items: page.items.map((item) => validateMessage(item, conversationId)),
  };
}

function validateTicket(ticket: ChatWebSocketTicket): ChatWebSocketTicket {
  if (
    !ticket.ticket
    || ticket.targetPath !== "/ws/chat"
    || ticket.queryParameter !== "ticket"
  ) {
    throw new ChatContractError(
      "Chat 实时票据的目标路径或查询参数不符合浏览器握手契约。",
    );
  }
  return ticket;
}

function ownedCustomerApi(
  ownerId: BusinessId,
  accessToken: string,
): ChatApi {
  const api = createChatWorkspaceApi(() => accessToken, apiBaseUrl);
  return {
    async createConversation(input) {
      return validateConversation(await api.createConversation(input), ownerId);
    },
    async conversations(limit) {
      return (await api.conversations(limit))
        .map((value) => validateConversation(value, ownerId));
    },
    async conversation(conversationId) {
      return validateConversation(
        await api.conversation(conversationId),
        ownerId,
      );
    },
    async claimConversation(conversationId) {
      return validateConversation(
        await api.claimConversation(conversationId),
        ownerId,
      );
    },
    async closeConversation(conversationId) {
      return validateConversation(
        await api.closeConversation(conversationId),
        ownerId,
      );
    },
    async messages(conversationId, beforeSequence, size) {
      return validateMessagePage(
        await api.messages(conversationId, beforeSequence, size),
        conversationId,
      );
    },
    async sendMessage(conversationId, input) {
      const message = validateMessage(
        await api.sendMessage(conversationId, input),
        conversationId,
      );
      if (message.senderId !== ownerId) {
        throw new ChatOwnershipMismatchError();
      }
      return message;
    },
    async markRead(conversationId, lastReadMessageId) {
      const fact = await api.markRead(conversationId, lastReadMessageId);
      if (
        fact.conversationId !== conversationId
        || typeof fact.lastReadMessageId !== "string"
      ) {
        throw new ChatContractError("Chat 已读回执无法归属到当前会话。");
      }
      return fact;
    },
    async createWebSocketTicket() {
      return validateTicket(await api.createWebSocketTicket());
    },
  };
}

export const useCustomerChatStore = defineStore("customer-chat", () => {
  const workspace = shallowRef<CustomerChatWorkspace | null>(null);

  function createWorkspace(
    ownerId: BusinessId,
    accessToken: string,
  ): CustomerChatWorkspace {
    const state = reactive(createChatWorkspaceState({
      pendingSendStorageKey: PENDING_SEND_KEY,
      pendingConversationStorageKey: PENDING_CONVERSATION_KEY,
    })) as ChatWorkspaceState;
    const controller = createChatWorkspaceController({
      state,
      api: ownedCustomerApi(ownerId, accessToken),
      currentUserId: () => ownerId,
      pendingSendStorageKey: PENDING_SEND_KEY,
      pendingConversationStorageKey: PENDING_CONVERSATION_KEY,
      apiBaseUrl,
    });
    return {
      ownerId,
      accessToken,
      state,
      controller,
    };
  }

  function synchronizeAccess(
    context: ChatAccessContext,
  ): CustomerChatWorkspace | null {
    if (!isActiveContext(context)) {
      workspace.value?.controller.disconnectRealtime();
      workspace.value = null;
      return null;
    }
    const current = workspace.value;
    if (
      current?.ownerId === context.ownerId
      && current.accessToken === context.accessToken
    ) {
      return current;
    }
    current?.controller.disconnectRealtime();
    workspace.value = createWorkspace(context.ownerId, context.accessToken);
    return workspace.value;
  }

  function requireWorkspace(
    context: ChatAccessContext,
  ): CustomerChatWorkspace {
    const current = synchronizeAccess(context);
    if (!current) {
      throw new ChatAccessChangedError();
    }
    return current;
  }

  function requireCurrent(current: CustomerChatWorkspace) {
    if (workspace.value !== current) {
      throw new ChatAccessChangedError();
    }
  }

  async function run<T>(
    context: ChatAccessContext,
    action: (controller: ChatWorkspaceController) => Promise<T>,
  ): Promise<T> {
    const current = requireWorkspace(context);
    const result = await action(current.controller);
    requireCurrent(current);
    return result;
  }

  const state = computed(() => workspace.value?.state ?? null);
  const ownerId = computed(() => workspace.value?.ownerId ?? null);
  const conversations = computed(() => state.value?.conversations ?? []);
  const activeConversationId = computed(() =>
    state.value?.activeConversationId ?? null);
  const messages = computed(() => state.value?.messages ?? []);
  const hasMore = computed(() => state.value?.hasMore ?? false);
  const loadingConversations = computed(() =>
    state.value?.loadingConversations ?? false);
  const loadingMessages = computed(() => state.value?.loadingMessages ?? false);
  const loadingOlder = computed(() => state.value?.loadingOlder ?? false);
  const sending = computed(() => state.value?.sending ?? false);
  const closingConversationId = computed(() =>
    state.value?.closingConversationId ?? null);
  const creatingConversation = computed(() =>
    state.value?.creatingConversation ?? false);
  const error = computed(() => state.value?.error ?? null);
  const sendError = computed(() => state.value?.sendError ?? null);
  const sendUnknown = computed(() => state.value?.sendUnknown ?? false);
  const readError = computed(() => state.value?.readError ?? null);
  const conversationCreationError = computed(() =>
    state.value?.conversationCreationError ?? null);
  const conversationCreationUnknown = computed(() =>
    state.value?.conversationCreationUnknown ?? false);
  const realtimeStatus = computed(() => state.value?.realtimeStatus ?? "idle");
  const realtimeMessage = computed(() =>
    state.value?.realtimeMessage ?? "实时连接尚未启动。");
  const pendingSend = computed<PendingChatSend | null>(() => {
    const pending = state.value?.pendingSend ?? null;
    return pending?.userId === ownerId.value ? pending : null;
  });
  const pendingConversation = computed<PendingChatConversation | null>(() => {
    const pending = state.value?.pendingConversation ?? null;
    return pending?.userId === ownerId.value ? pending : null;
  });
  const hasForeignPendingSend = computed(() => Boolean(
    state.value?.pendingSend
    && state.value.pendingSend.userId !== ownerId.value,
  ));
  const hasForeignPendingConversation = computed(() => Boolean(
    state.value?.pendingConversation
    && state.value.pendingConversation.userId !== ownerId.value,
  ));
  const activeConversation = computed(() =>
    workspace.value?.controller.activeConversation() ?? null);

  function setActiveConversation(
    context: ChatAccessContext,
    conversationId: BusinessId | null,
  ) {
    requireWorkspace(context).controller.setActiveConversation(conversationId);
  }

  function loadConversations(context: ChatAccessContext) {
    return run(context, (controller) => controller.loadConversations());
  }

  function loadActiveConversation(context: ChatAccessContext) {
    return run(context, (controller) => controller.loadActiveConversation());
  }

  function loadMessages(
    context: ChatAccessContext,
    conversationId?: BusinessId,
  ) {
    return run(context, (controller) => controller.loadMessages(conversationId));
  }

  function loadOlder(context: ChatAccessContext) {
    return run(context, (controller) => controller.loadOlder());
  }

  function refreshActive(context: ChatAccessContext) {
    return run(context, (controller) => controller.refreshActive());
  }

  function createConversation(context: ChatAccessContext, subject: string) {
    return run(context, (controller) => controller.createConversation(subject));
  }

  function retryPendingConversation(context: ChatAccessContext) {
    return run(context, (controller) => controller.retryPendingConversation());
  }

  function closeConversation(context: ChatAccessContext) {
    return run(context, (controller) => controller.closeConversation());
  }

  function sendText(context: ChatAccessContext, content: string) {
    return run(context, (controller) => controller.sendText(content));
  }

  function retryPendingSend(context: ChatAccessContext) {
    return run(context, (controller) => controller.retryPendingSend());
  }

  function markLatestRead(context: ChatAccessContext) {
    return run(context, (controller) => controller.markLatestRead());
  }

  function connectRealtime(context: ChatAccessContext) {
    requireWorkspace(context).controller.connectRealtime();
  }

  function disconnectRealtime() {
    workspace.value?.controller.disconnectRealtime();
  }

  function restartRealtime(context: ChatAccessContext) {
    requireWorkspace(context).controller.restartRealtime();
  }

  return {
    ownerId,
    conversations,
    activeConversationId,
    activeConversation,
    messages,
    hasMore,
    loadingConversations,
    loadingMessages,
    loadingOlder,
    sending,
    closingConversationId,
    creatingConversation,
    error,
    sendError,
    sendUnknown,
    readError,
    conversationCreationError,
    conversationCreationUnknown,
    realtimeStatus,
    realtimeMessage,
    pendingSend,
    pendingConversation,
    hasForeignPendingSend,
    hasForeignPendingConversation,
    synchronizeAccess,
    setActiveConversation,
    loadConversations,
    loadActiveConversation,
    loadMessages,
    loadOlder,
    refreshActive,
    createConversation,
    retryPendingConversation,
    closeConversation,
    sendText,
    retryPendingSend,
    markLatestRead,
    connectRealtime,
    disconnectRealtime,
    restartRealtime,
  };
});
