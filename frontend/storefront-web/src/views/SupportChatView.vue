<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  ref,
  watch,
} from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import type { ChatMessage } from "@plain-journal/foundation";

import {
  ChatAccessChangedError,
  type ChatAccessContext,
  useCustomerChatStore,
} from "../entities/chat";
import { useSessionStore } from "../features/customer-session";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const chat = useCustomerChatStore();
const draft = ref("");
const subject = ref("");
const messageLog = ref<HTMLElement | null>(null);
const accessContext = computed<ChatAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const active = computed(() => chat.activeConversation);
const realtimePresentation = computed(() => ({
  idle: {
    tone: "neutral" as const,
    title: "实时连接已关闭",
  },
  connecting: {
    tone: "processing" as const,
    title: "正在连接实时更新",
  },
  connected: {
    tone: "success" as const,
    title: "实时更新可用",
  },
  reconnecting: {
    tone: "processing" as const,
    title: "正在恢复实时更新",
  },
  unavailable: {
    tone: "warning" as const,
    title: "实时更新暂不可用",
  },
}[chat.realtimeStatus]));
const workspaceErrorTone = computed(() =>
  chat.error?.includes("尚未确认") || chat.error?.includes("暂时无法确认")
    ? "unknown" as const
    : "danger" as const);

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function conversationStatus(value: string): string {
  return value === "OPEN" ? "处理中" : value === "CLOSED" ? "已结束" : value;
}

function messageStatus(message: ChatMessage): string {
  return {
    STORED: "已保存",
    PERSISTED: "已保存",
    DISPATCHED: "已进入投递",
    DELIVERED: "已送达",
    READ: "已读",
  }[message.status] ?? message.status;
}

async function activateRoute(context = accessContext.value) {
  try {
    const conversationId = typeof route.params.conversationId === "string"
      ? route.params.conversationId
      : null;
    chat.setActiveConversation(context, conversationId);
    if (!conversationId) {
      return;
    }
    await Promise.all([
      chat.loadActiveConversation(context),
      chat.loadMessages(context, conversationId),
    ]);
    await chat.markLatestRead(context);
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function createConversation() {
  try {
    const value = await chat.createConversation(accessContext.value, subject.value);
    if (!value) {
      return;
    }
    subject.value = "";
    await router.push({
      name: "support-chat-detail",
      params: { conversationId: value.id },
    });
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function retryConversation() {
  try {
    const value = await chat.retryPendingConversation(accessContext.value);
    if (!value) {
      return;
    }
    subject.value = "";
    await router.push({
      name: "support-chat-detail",
      params: { conversationId: value.id },
    });
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function sendMessage() {
  try {
    const value = await chat.sendText(accessContext.value, draft.value);
    if (value) {
      draft.value = "";
    }
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function retrySend() {
  try {
    const value = await chat.retryPendingSend(accessContext.value);
    if (value) {
      draft.value = "";
    }
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function closeActiveConversation() {
  try {
    await chat.closeConversation(accessContext.value);
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

async function reloadWorkspace() {
  try {
    await chat.refreshActive(accessContext.value);
  } catch (cause) {
    if (!(cause instanceof ChatAccessChangedError)) {
      // The current workspace exposes its actionable error state.
    }
  }
}

watch(
  accessContext,
  async (context) => {
    chat.synchronizeAccess(context);
    draft.value = chat.pendingSend?.content ?? "";
    subject.value = chat.pendingConversation?.subject ?? "";
    if (!context.authenticated) {
      return;
    }
    try {
      await chat.loadConversations(context);
      await activateRoute(context);
      chat.connectRealtime(context);
    } catch (cause) {
      if (!(cause instanceof ChatAccessChangedError)) {
        // The current workspace exposes its actionable error state.
      }
    }
  },
  { immediate: true },
);

watch(
  () => route.params.conversationId,
  () => {
    void activateRoute();
  },
);

watch(
  () => chat.messages.length,
  async () => {
    await nextTick();
    if (messageLog.value) {
      messageLog.value.scrollTop = messageLog.value.scrollHeight;
    }
  },
);

onBeforeUnmount(() => {
  chat.disconnectRealtime();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="support-chat-page">
    <nav class="support-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>联系素简记</span>
    </nav>

    <header class="support-intro">
      <div>
        <p class="support-context">顾客支持</p>
        <h1>联系素简记</h1>
        <p>消息先写入可靠历史；实时连接只负责尽快把新事实带到当前页面。</p>
      </div>
      <PjStatusNotice
        class="support-realtime"
        :tone="realtimePresentation.tone"
        :title="realtimePresentation.title"
      >
        <p>{{ chat.realtimeMessage }}</p>
        <template v-if="chat.realtimeStatus !== 'connected'" #actions>
          <PjButton
            variant="text"
            @click="chat.restartRealtime(accessContext)"
          >
            重新连接
          </PjButton>
        </template>
      </PjStatusNotice>
    </header>

    <PjStatusNotice
      v-if="chat.error"
      class="support-error"
      :tone="workspaceErrorTone"
      :title="workspaceErrorTone === 'unknown' ? '会话结果待确认' : '会话事实未能读取'"
      :assertive="workspaceErrorTone === 'danger'"
    >
      <p>{{ chat.error }}</p>
      <template #actions>
        <PjButton variant="text" @click="reloadWorkspace">重新读取会话</PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="chat.hasForeignPendingSend || chat.hasForeignPendingConversation"
      class="support-owner-boundary"
      tone="attention"
      title="这个设备上有另一账户的待确认操作"
    >
      <p>当前账户不会读取其主题、正文或重试键，也不会替另一账户查询或重提。</p>
    </PjStatusNotice>

    <div class="support-workspace">
      <PjSurface
        as="aside"
        tone="soft"
        padding="medium"
        class="support-conversations"
        aria-label="我的会话"
      >
        <header class="support-section-heading">
          <div>
            <p class="support-context">会话</p>
            <h2>正在处理的事项</h2>
          </div>
          <PjButton
            variant="text"
            :loading="chat.loadingConversations"
            @click="chat.loadConversations(accessContext)"
          >
            刷新
          </PjButton>
        </header>

        <PjStatusNotice
          v-if="chat.loadingConversations && chat.conversations.length === 0"
          tone="processing"
          title="正在读取会话"
        >
          <p>只展示当前账户可访问的会话事实。</p>
        </PjStatusNotice>
        <PjStatusNotice
          v-else-if="chat.conversations.length === 0"
          tone="neutral"
          title="还没有会话"
        >
          <p>说明需要帮助的事项后，再建立联系。</p>
        </PjStatusNotice>

        <nav v-else class="support-conversation-list" aria-label="会话列表">
          <RouterLink
            v-for="item in chat.conversations"
            :key="item.id"
            :to="{ name: 'support-chat-detail', params: { conversationId: item.id } }"
          >
            <span>
              <strong>{{ item.subject }}</strong>
              <small>
                {{ item.assignedAgentId ? "客服已接入" : "等待客服接入" }}
                · {{ conversationStatus(item.status) }}
              </small>
            </span>
            <span v-if="item.unreadCount > 0" class="support-unread">
              {{ item.unreadCount }} 条未读
            </span>
          </RouterLink>
        </nav>

        <form class="support-new-conversation" @submit.prevent="createConversation">
          <PjField
            v-slot="{ describedBy, invalid }"
            label="这次需要什么帮助？"
            for-id="support-subject"
            hint="请只写事项主题，进入会话后再补充具体事实。"
            required
          >
            <textarea
              id="support-subject"
              v-model.trim="subject"
              class="pj-control"
              required
              maxlength="160"
              rows="3"
              :disabled="Boolean(chat.pendingConversation) || chat.hasForeignPendingConversation"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
              placeholder="例如：想确认帆布通勤袋的清洁与保养方式"
            />
          </PjField>
          <PjButton
            type="submit"
            variant="secondary"
            :loading="chat.creatingConversation"
            :disabled="Boolean(chat.pendingConversation) || chat.hasForeignPendingConversation"
          >
            {{ chat.creatingConversation ? "正在建立…" : "建立会话" }}
          </PjButton>
        </form>

        <PjStatusNotice
          v-if="chat.conversationCreationError"
          class="support-creation-result"
          :tone="chat.conversationCreationUnknown ? 'unknown' : 'danger'"
          :title="chat.conversationCreationUnknown ? '会话创建结果待确认' : '会话未能建立'"
          :assertive="!chat.conversationCreationUnknown"
        >
          <p>{{ chat.conversationCreationError }}</p>
          <template v-if="chat.pendingConversation" #actions>
            <PjButton
              variant="text"
              :loading="chat.creatingConversation"
              @click="retryConversation"
            >
              使用原创建键再次确认
            </PjButton>
          </template>
        </PjStatusNotice>
      </PjSurface>

      <PjSurface
        v-if="active"
        as="section"
        tone="plain"
        padding="medium"
        class="support-thread"
        aria-labelledby="support-thread-title"
      >
        <header class="support-thread__header">
          <div>
            <p class="support-context">{{ active.conversationNo }}</p>
            <h2 id="support-thread-title">{{ active.subject }}</h2>
          </div>
          <div class="support-thread__status">
            <strong>{{ conversationStatus(active.status) }}</strong>
            <span>{{ active.assignedAgentId ? "客服已接入" : "等待客服接入" }}</span>
            <PjButton
              v-if="active.status === 'OPEN'"
              variant="text"
              :loading="chat.closingConversationId === active.id"
              @click="closeActiveConversation"
            >
              {{
                chat.closingConversationId === active.id
                  ? "正在确认关闭…"
                  : "结束会话"
              }}
            </PjButton>
          </div>
        </header>

        <PjButton
          v-if="chat.hasMore"
          class="support-load-older"
          variant="text"
          :loading="chat.loadingOlder"
          @click="chat.loadOlder(accessContext)"
        >
          {{ chat.loadingOlder ? "正在读取…" : "读取更早消息" }}
        </PjButton>

        <div
          ref="messageLog"
          class="support-message-log"
          role="log"
          aria-live="polite"
          aria-relevant="additions text"
        >
          <PjStatusNotice
            v-if="chat.loadingMessages && chat.messages.length === 0"
            tone="processing"
            title="正在读取消息历史"
          >
            <p>页面以服务端持久化历史为准。</p>
          </PjStatusNotice>
          <PjStatusNotice
            v-else-if="chat.messages.length === 0"
            tone="neutral"
            title="会话已经建立"
          >
            <p>发送第一条文本消息后，客服才能了解具体问题。</p>
          </PjStatusNotice>
          <article
            v-for="message in chat.messages"
            :key="message.id"
            class="support-message"
            :class="{ 'support-message--self': message.senderId === session.profile?.id }"
          >
            <header>
              <strong>
                {{ message.senderId === session.profile?.id ? "我" : "素简记客服" }}
              </strong>
              <span>{{ formatTimestamp(message.createdAt) }}</span>
            </header>
            <p>{{ message.content }}</p>
            <PjStatusNotice
              v-if="message.attachments.length > 0"
              class="support-attachment-boundary"
              tone="attention"
              title="附件仍在安全边界内"
            >
              <p>附件下载入口尚未开放；扫描、隔离与授权结果不能由实时消息替代。</p>
            </PjStatusNotice>
            <footer>{{ messageStatus(message) }} · 序号 {{ message.sequence }}</footer>
          </article>
        </div>

        <form
          v-if="active.status === 'OPEN'"
          class="support-composer"
          @submit.prevent="sendMessage"
        >
          <PjField
            v-slot="{ describedBy, invalid }"
            label="回复内容"
            for-id="customer-chat-message"
            hint="发送成功只表示消息已持久化；实时连接不是成功凭据。"
            required
          >
            <textarea
              id="customer-chat-message"
              v-model="draft"
              class="pj-control"
              required
              maxlength="4000"
              rows="4"
              :disabled="chat.sending || chat.sendUnknown || chat.hasForeignPendingSend"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
              placeholder="写下需要客服了解的事实"
            />
          </PjField>
          <PjActionGroup>
            <PjButton
              type="submit"
              :loading="chat.sending"
              :disabled="chat.sendUnknown || chat.hasForeignPendingSend || !draft.trim()"
            >
              {{ chat.sending ? "正在确认消息事实…" : "发送消息" }}
            </PjButton>
          </PjActionGroup>
        </form>
        <PjStatusNotice
          v-else
          tone="neutral"
          title="会话已经结束"
        >
          <p>历史消息仍可读取，但不能继续发送。</p>
        </PjStatusNotice>

        <PjStatusNotice
          v-if="chat.sendError"
          class="support-send-result"
          :tone="chat.sendUnknown ? 'unknown' : 'danger'"
          :title="chat.sendUnknown ? '消息发送结果待确认' : '消息未能发送'"
          :assertive="!chat.sendUnknown"
        >
          <p>{{ chat.sendError }}</p>
          <template v-if="chat.pendingSend" #actions>
            <PjButton
              variant="text"
              :loading="chat.sending"
              @click="retrySend"
            >
              使用原消息键查询并重试
            </PjButton>
          </template>
        </PjStatusNotice>

        <PjStatusNotice
          v-if="chat.readError"
          class="support-read-result"
          tone="warning"
          title="已读位置未能确认"
        >
          <p>{{ chat.readError }}</p>
          <p>消息历史不受影响；未读数量会保留，直到可靠已读命令成功。</p>
        </PjStatusNotice>
      </PjSurface>

      <PjSurface
        v-else
        as="section"
        tone="plain"
        padding="large"
        class="support-thread support-thread--empty"
      >
        <p class="support-context">选择会话</p>
        <h2>会话内容只在进入具体地址后读取。</h2>
        <p>选择左侧事项，或建立一个新的会话。刷新页面后仍可恢复同一地址。</p>
      </PjSurface>
    </div>
  </PjPageContainer>
</template>

<style scoped>
.support-chat-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.support-path {
  display: flex;
  gap: var(--pj-space-2);
  margin-bottom: var(--pj-space-7);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.support-intro {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.55fr);
  gap: var(--pj-space-7);
  align-items: start;
  margin-bottom: var(--pj-space-6);
}

.support-intro h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
  line-height: var(--pj-line-height-tight);
}

.support-intro > div > p:last-child {
  max-width: var(--pj-layout-reading);
  margin: var(--pj-space-4) 0 0;
  color: var(--pj-text-secondary);
}

.support-context {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.support-realtime {
  align-self: stretch;
}

.support-error,
.support-owner-boundary {
  margin-bottom: var(--pj-space-5);
}

.support-workspace {
  display: grid;
  grid-template-columns: minmax(18rem, 0.36fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
  align-items: stretch;
}

.support-conversations,
.support-thread {
  min-width: 0;
}

.support-section-heading,
.support-thread__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding-bottom: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.support-section-heading h2,
.support-thread h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
}

.support-conversation-list {
  display: grid;
}

.support-conversation-list a {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-3);
  padding-block: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-subtle);
  text-decoration: none;
}

.support-conversation-list a.router-link-active {
  color: var(--pj-brand-primary-hover);
}

.support-conversation-list a > span:first-child {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
}

.support-conversation-list strong {
  overflow-wrap: anywhere;
}

.support-conversation-list small,
.support-unread,
.support-thread__status,
.support-message footer {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.support-unread {
  flex: 0 0 auto;
}

.support-new-conversation,
.support-composer {
  display: grid;
  gap: var(--pj-space-4);
  margin-top: var(--pj-space-6);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.support-creation-result,
.support-send-result,
.support-read-result {
  margin-top: var(--pj-space-4);
}

.support-thread {
  min-height: 42rem;
  display: flex;
  flex-direction: column;
}

.support-thread__status {
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
}

.support-thread__status strong {
  color: var(--pj-text-primary);
}

.support-load-older {
  align-self: center;
  margin-block: var(--pj-space-3);
}

.support-message-log {
  min-height: 22rem;
  max-height: 34rem;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-block: var(--pj-space-3);
}

.support-message {
  max-width: 44rem;
  display: grid;
  gap: var(--pj-space-2);
  padding: var(--pj-space-4) var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.support-message--self {
  margin-left: auto;
  border-left: 0.2rem solid var(--pj-brand-primary);
}

.support-message header {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-4);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.support-message p,
.support-message footer {
  margin: 0;
}

.support-message > p {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  line-height: var(--pj-line-height-relaxed);
}

.support-attachment-boundary {
  margin-block: var(--pj-space-2);
}

.support-composer {
  margin-top: auto;
}

.support-thread--empty {
  justify-content: center;
}

.support-thread--empty > p:last-child {
  max-width: var(--pj-layout-reading);
  color: var(--pj-text-secondary);
}

@media (max-width: 64rem) {
  .support-intro {
    grid-template-columns: minmax(0, 1fr) minmax(18rem, 0.65fr);
    gap: var(--pj-space-5);
  }
}

@media (max-width: 48rem) {
  .support-intro,
  .support-workspace {
    grid-template-columns: 1fr;
  }

  .support-thread {
    min-height: 34rem;
  }

  .support-message-log {
    max-height: 30rem;
  }
}

@media (max-width: 32rem) {
  .support-section-heading,
  .support-thread__header {
    flex-direction: column;
  }

  .support-thread__status {
    justify-items: start;
  }

  .support-message {
    padding-inline: 0;
  }
}
</style>
