<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { RouterLink, useRoute } from "vue-router";

import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  useAdminChatStore,
  type AdminChatOperationPhase,
} from "../entities/admin-chat";
import { useStaffSessionStore } from "../stores/session";

const route = useRoute();
const session = useStaffSessionStore();
const chat = useAdminChatStore();
const draft = ref("");
const messageLog = ref<HTMLElement | null>(null);
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const routeConversationId = computed(() => {
  const value = route.params.conversationId;
  return typeof value === "string" && /^[0-9]+$/u.test(value)
    ? value
    : null;
});

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatBytes(value: number): string {
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function assignmentLabel(assignedAgentId: string | null): string {
  if (!assignedAgentId) {
    return "等待认领";
  }
  return assignedAgentId === chat.operatorId
    ? "由我处理"
    : "其他客服处理中";
}

function messageStatus(value: string): string {
  return {
    STORED: "已保存",
    PERSISTED: "已保存",
    DISPATCHED: "已进入投递",
    DELIVERED: "已送达",
    READ: "已读",
  }[value] ?? value;
}

function operationTone(phase: AdminChatOperationPhase) {
  return {
    idle: "neutral",
    processing: "processing",
    unknown: "unknown",
    accepted: "success",
    rejected: "danger",
  }[phase] as
    | "neutral"
    | "processing"
    | "unknown"
    | "success"
    | "danger";
}

function operationTitle(phase: AdminChatOperationPhase) {
  return {
    idle: "客服操作",
    processing: "客服操作正在确认",
    unknown: "客服操作结果未知",
    accepted: "客服操作已确认",
    rejected: "客服操作未被接受",
  }[phase];
}

async function refreshWorkspace() {
  await chat.refresh(accessContext.value, routeConversationId.value);
}

async function claim() {
  const active = chat.activeConversation;
  if (active) {
    await chat.claimConversation(accessContext.value, active.id);
  }
}

async function sendMessage() {
  const value = await chat.sendText(accessContext.value, draft.value);
  if (value) {
    draft.value = "";
  }
}

async function retrySend() {
  const value = await chat.retryPendingSend(accessContext.value);
  if (value) {
    draft.value = "";
  }
}

async function closeActiveConversation() {
  await chat.closeConversation(accessContext.value);
}

watch(accessContext, (context) => {
  void chat.updateAccess(context);
});

watch(routeConversationId, (conversationId) => {
  void chat.updateRoute(accessContext.value, conversationId);
});

watch(
  () => [chat.operatorId, chat.pendingSend?.clientMessageId] as const,
  ([operatorId, pendingKey], previous) => {
    const previousOperatorId = previous?.[0] ?? null;
    if (operatorId !== previousOperatorId) {
      draft.value = chat.pendingSend?.content ?? "";
      return;
    }
    if (pendingKey) {
      draft.value = chat.pendingSend?.content ?? "";
    }
  },
  { immediate: true },
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

onMounted(() => {
  void chat.start(accessContext.value, routeConversationId.value);
});

onBeforeUnmount(() => {
  chat.stop();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="chat-page">
    <header class="chat-hero">
      <div>
        <p class="eyebrow">Chat 所有者域</p>
        <h1>客服会话</h1>
        <p>
          队列只暴露会话摘要。客服成为会话成员后，页面才读取 MySQL 中的私聊正文；
          WebSocket 负责提示新事实，不承担发送成功或最终状态证明。
        </p>
      </div>
      <span
        class="chat-realtime"
        :data-connected="chat.realtimeStatus === 'connected'"
      >
        {{ chat.realtimeStatus === "connected" ? "实时可用" : "历史查询可用" }}
      </span>
    </header>

    <PjStatusNotice
      :tone="chat.realtimeStatus === 'connected' ? 'neutral' : 'warning'"
      title="可靠 REST 与实时提示保持分工"
    >
      <p>
        消息先通过可靠 REST 持久化，再由实时链路提示页面刷新。实时连接失败不会被解释为
        消息未保存或已送达；连接恢复使用新的短期单次票据。
      </p>
      <p>{{ chat.realtimeMessage }}</p>
      <template v-if="chat.realtimeStatus !== 'connected'" #actions>
        <PjButton
          type="button"
          variant="text"
          @click="chat.restartRealtime(accessContext)"
        >
          重新建立实时连接
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="chat.operationPhase !== 'idle' && chat.operationMessage"
      :tone="operationTone(chat.operationPhase)"
      :title="operationTitle(chat.operationPhase)"
      :assertive="chat.operationPhase === 'rejected'"
    >
      <p>{{ chat.operationMessage }}</p>
      <p v-if="chat.pendingOperation">
        待确认操作：{{ chat.pendingOperation.kind === "claim" ? "认领" : "关闭" }}；
        会话 <code>{{ chat.pendingOperation.conversationId }}</code>。
      </p>
      <template #actions>
        <PjActionGroup>
          <PjButton
            v-if="chat.canRetryOperation"
            type="button"
            :loading="
              chat.claimingConversationId !== null
                || chat.closingConversationId !== null
            "
            @click="chat.retryPendingOperation(accessContext)"
          >
            对同一会话安全重试
          </PjButton>
          <PjButton
            v-if="!chat.pendingOperation"
            type="button"
            variant="text"
            @click="chat.resetOperationNotice"
          >
            关闭提示
          </PjButton>
        </PjActionGroup>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="chat.error"
      tone="danger"
      title="Chat 事实读取未完成"
      assertive
    >
      <p>{{ chat.error }}</p>
      <p v-if="chat.conversations.length > 0">
        页面保留已经确认的队列事实，没有把读取失败伪装成空队列。
      </p>
    </PjStatusNotice>

    <div class="chat-workspace">
      <PjSurface
        as="aside"
        tone="plain"
        padding="none"
        class="chat-queue"
      >
        <header class="chat-panel-header">
          <div>
            <p class="eyebrow">OPEN 队列</p>
            <h2>等待与已认领会话</h2>
          </div>
          <PjButton
            type="button"
            variant="text"
            :loading="chat.loadingConversations"
            @click="refreshWorkspace"
          >
            重新读取
          </PjButton>
        </header>

        <div
          v-if="chat.loadingConversations && chat.conversations.length === 0"
          class="chat-state"
          role="status"
        >
          正在读取客服队列…
        </div>
        <div
          v-else-if="chat.conversations.length === 0"
          class="chat-state"
        >
          当前没有开放会话。
        </div>
        <nav v-else class="chat-list" aria-label="客服会话队列">
          <RouterLink
            v-for="item in chat.conversations"
            :key="item.id"
            :to="{
              name: 'chat-workspace-detail',
              params: { conversationId: item.id },
            }"
          >
            <span>
              <strong>{{ item.subject }}</strong>
              <small>{{ item.conversationNo }}</small>
              <small>顾客 {{ item.customerId }}</small>
            </span>
            <span>
              {{ assignmentLabel(item.assignedAgentId) }}
              <small v-if="item.unreadCount > 0">{{ item.unreadCount }} 条未读</small>
            </span>
          </RouterLink>
        </nav>
      </PjSurface>

      <PjSurface
        v-if="chat.activeConversation"
        as="section"
        tone="plain"
        padding="none"
        class="chat-thread"
      >
        <header class="chat-panel-header chat-thread__header">
          <div>
            <p class="eyebrow">{{ chat.activeConversation.conversationNo }}</p>
            <h2>{{ chat.activeConversation.subject }}</h2>
          </div>
          <div class="chat-thread__status">
            <span class="status-label">{{ chat.activeConversation.status }}</span>
            <PjButton
              v-if="
                chat.assignedToMe
                && chat.activeConversation.status === 'OPEN'
              "
              type="button"
              variant="text"
              :loading="
                chat.closingConversationId === chat.activeConversation.id
              "
              :disabled="chat.operationBlocked || chat.sendUnknown"
              @click="closeActiveConversation"
            >
              结束会话
            </PjButton>
          </div>
        </header>

        <dl class="chat-facts">
          <div>
            <dt>顾客</dt>
            <dd><code>{{ chat.activeConversation.customerId }}</code></dd>
          </div>
          <div>
            <dt>当前客服</dt>
            <dd>
              <code>{{ chat.activeConversation.assignedAgentId || "尚未认领" }}</code>
            </dd>
          </div>
          <div>
            <dt>最后序号</dt>
            <dd>{{ chat.activeConversation.lastMessageSequence }}</dd>
          </div>
          <div>
            <dt>未读</dt>
            <dd>{{ chat.activeConversation.unreadCount }}</dd>
          </div>
        </dl>

        <PjSurface
          v-if="!chat.activeConversation.assignedAgentId"
          tone="soft"
          padding="large"
          class="chat-claim"
        >
          <div>
            <p class="eyebrow">成员权限边界</p>
            <h3>认领成功前不读取正文</h3>
            <p>
              认领会在 Chat 事务中锁定会话并写入客服成员事实。只有该事实确认后，
              当前员工才能读取消息、发送回复和推进已读位置。
            </p>
          </div>
          <PjButton
            type="button"
            :loading="
              chat.claimingConversationId === chat.activeConversation.id
            "
            :disabled="chat.operationBlocked"
            @click="claim"
          >
            认领并读取会话
          </PjButton>
        </PjSurface>

        <PjStatusNotice
          v-else-if="chat.assignedToOther"
          tone="warning"
          title="该会话属于其他客服"
        >
          <p>
            该会话已由客服 {{ chat.activeConversation.assignedAgentId }} 认领。
            当前账号不能读取正文、回复或结束会话。
          </p>
        </PjStatusNotice>

        <template v-else-if="chat.assignedToMe">
          <div class="chat-history-toolbar">
            <PjButton
              v-if="chat.hasMore"
              type="button"
              variant="text"
              :loading="chat.loadingOlder"
              @click="chat.loadOlder(accessContext)"
            >
              读取更早消息
            </PjButton>
            <span>
              {{ chat.messages.length }} 条已读取消息 · MySQL 历史为权威
            </span>
          </div>

          <div
            ref="messageLog"
            class="chat-log"
            role="log"
            aria-live="polite"
            aria-relevant="additions text"
          >
            <div
              v-if="chat.loadingMessages && chat.messages.length === 0"
              class="chat-state"
            >
              正在读取 MySQL 消息历史…
            </div>
            <div
              v-else-if="!chat.loadingMessages && chat.messages.length === 0"
              class="chat-state"
            >
              当前会话尚无可显示消息。
            </div>
            <article
              v-for="message in chat.messages"
              :key="message.id"
              class="chat-message"
              :class="{
                'chat-message--self': message.senderId === chat.operatorId,
              }"
            >
              <header>
                <strong>
                  {{
                    message.senderId === chat.operatorId
                      ? "我"
                      : `顾客 ${chat.activeConversation.customerId}`
                  }}
                </strong>
                <span>{{ formatTimestamp(message.createdAt) }}</span>
              </header>
              <p>{{ message.content }}</p>
              <ul
                v-if="message.attachments.length > 0"
                class="chat-attachments"
                aria-label="消息附件元数据"
              >
                <li
                  v-for="attachment in message.attachments"
                  :key="attachment.id"
                >
                  <strong>{{ attachment.fileName }}</strong>
                  <span>
                    {{ attachment.mimeType }} · {{ formatBytes(attachment.sizeBytes) }}
                  </span>
                  <small>
                    只有完成隔离扫描并绑定到消息的附件才会出现在历史投影中；
                    当前客服界面不开放下载动作。
                  </small>
                </li>
              </ul>
              <footer>
                {{ messageStatus(message.status) }} · 序号 {{ message.sequence }}
              </footer>
            </article>
          </div>

          <form
            v-if="chat.activeConversation.status === 'OPEN'"
            class="chat-composer"
            @submit.prevent="sendMessage"
          >
            <PjField
              v-slot="{ describedBy }"
              label="回复顾客"
              for-id="staff-chat-message"
              hint="1–4000 字。结果未知时正文与原客户端消息键会被冻结。"
              required
            >
              <textarea
                id="staff-chat-message"
                v-model="draft"
                class="pj-control"
                required
                maxlength="4000"
                rows="5"
                :readonly="chat.sending || chat.sendUnknown"
                :aria-describedby="describedBy"
              ></textarea>
            </PjField>
            <PjActionGroup>
              <PjButton
                type="submit"
                :loading="chat.sending && !chat.sendUnknown"
                :disabled="
                  chat.sending
                  || chat.sendUnknown
                  || !draft.trim()
                  || chat.operationBlocked
                "
              >
                发送客服回复
              </PjButton>
              <span class="chat-composer__boundary">
                WebSocket 在线状态不是发送成功凭据。
              </span>
            </PjActionGroup>
          </form>
          <div v-else class="chat-closed">
            该会话已经关闭，历史消息保留只读。
          </div>

          <PjStatusNotice
            v-if="chat.sendError"
            :tone="chat.sendUnknown ? 'unknown' : 'danger'"
            :title="chat.sendUnknown ? '客服回复结果未知' : '客服回复未发送'"
            :assertive="!chat.sendUnknown"
          >
            <p>{{ chat.sendError }}</p>
            <p v-if="chat.pendingSend">
              原客户端消息键：<code>{{ chat.pendingSend.clientMessageId }}</code>。
              页面不会用新正文覆盖它。
            </p>
            <template v-if="chat.pendingSend" #actions>
              <PjButton
                type="button"
                :loading="chat.sending"
                @click="retrySend"
              >
                使用原消息键查询并重试
              </PjButton>
            </template>
          </PjStatusNotice>

          <PjStatusNotice
            v-if="chat.readError"
            tone="warning"
            title="已读位置尚未确认"
          >
            <p>{{ chat.readError }}</p>
            <p>页面不会提前把未读数清零；已读位置只按服务端单调事实推进。</p>
            <template #actions>
              <PjButton
                type="button"
                variant="text"
                @click="chat.retryRead(accessContext)"
              >
                重新确认已读位置
              </PjButton>
            </template>
          </PjStatusNotice>
        </template>
      </PjSurface>

      <PjSurface
        v-else
        as="section"
        tone="soft"
        padding="large"
        class="chat-thread chat-thread--empty"
      >
        <p class="eyebrow">选择会话</p>
        <h2>队列摘要与私聊正文保持分离。</h2>
        <p>
          选择一条会话查看成员状态。未认领时页面不会提前请求消息正文；
          已关闭但仍属于当前员工的会话可通过原 URL 读取只读历史。
        </p>
      </PjSurface>
    </div>
  </PjPageContainer>
</template>

<style scoped>
.chat-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.chat-hero,
.chat-panel-header,
.chat-thread__status,
.chat-history-toolbar,
.chat-message > header,
.chat-claim {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.chat-hero {
  align-items: flex-end;
}

.chat-hero h1,
.chat-panel-header h2,
.chat-claim h3,
.chat-thread--empty h2 {
  margin: 0;
}

.chat-hero h1 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.chat-hero p:last-child,
.chat-claim p:last-child,
.chat-thread--empty p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-color-muted);
  line-height: var(--pj-line-height-relaxed);
}

.chat-realtime {
  flex: 0 0 auto;
  padding: var(--pj-space-1) var(--pj-space-3);
  border: 1px solid var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
  font-size: var(--pj-font-size-sm);
}

.chat-realtime[data-connected="true"] {
  border-color: var(--pj-status-success-line);
  color: var(--pj-status-success-text);
}

.chat-workspace {
  min-width: 0;
  min-height: 44rem;
  display: grid;
  grid-template-columns: minmax(20rem, 0.34fr) minmax(0, 1fr);
  border-block: 1px solid var(--pj-color-line);
}

.chat-queue,
.chat-thread {
  min-width: 0;
}

.chat-queue {
  border-right: 1px solid var(--pj-color-line);
}

.chat-panel-header,
.chat-facts,
.chat-claim,
.chat-history-toolbar,
.chat-log,
.chat-composer,
.chat-closed,
.chat-thread > .pj-status-notice {
  margin-inline: var(--pj-space-6);
}

.chat-panel-header {
  padding-block: var(--pj-space-6);
}

.chat-panel-header h2,
.chat-claim h3,
.chat-thread--empty h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 540;
}

.chat-list {
  display: grid;
}

.chat-list a {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding: var(--pj-space-4) var(--pj-space-6);
  border-top: 1px solid var(--pj-color-line);
  color: inherit;
  text-decoration: none;
}

.chat-list a:last-child {
  border-bottom: 1px solid var(--pj-color-line);
}

.chat-list a:hover {
  background: var(--pj-color-surface-soft);
}

.chat-list a.router-link-active {
  box-shadow: inset 0.2rem 0 0 var(--pj-color-accent);
  background: var(--pj-color-surface-soft);
  color: var(--pj-color-accent-strong);
}

.chat-list a > span {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
}

.chat-list a > span:last-child {
  flex: 0 0 auto;
  justify-items: end;
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
  text-align: right;
}

.chat-list strong,
.chat-list small,
.chat-message p,
.chat-message footer,
.chat-attachments li,
.chat-facts dd {
  overflow-wrap: anywhere;
}

.chat-list small,
.chat-message footer,
.chat-history-toolbar,
.chat-composer__boundary {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.chat-state {
  min-height: 10rem;
  display: grid;
  place-items: center;
  padding: var(--pj-space-5);
  color: var(--pj-color-muted);
  text-align: center;
}

.chat-thread {
  display: flex;
  flex-direction: column;
}

.chat-thread__header {
  border-bottom: 1px solid var(--pj-color-line);
}

.chat-thread__header > div:first-child {
  min-width: 0;
}

.chat-thread__header h2 {
  overflow-wrap: anywhere;
}

.chat-thread__status {
  align-items: center;
  gap: var(--pj-space-3);
}

.chat-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin-block: 0;
  padding-block: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-color-line);
}

.chat-facts div {
  min-width: 0;
}

.chat-facts dt {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.chat-facts dd {
  margin: var(--pj-space-1) 0 0;
}

.chat-claim {
  align-items: center;
  margin-block: var(--pj-space-6);
}

.chat-claim > div {
  min-width: 0;
}

.chat-history-toolbar {
  align-items: center;
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-color-line);
}

.chat-log {
  min-height: 22rem;
  max-height: 36rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.chat-message {
  max-width: 46rem;
  display: grid;
  gap: var(--pj-space-2);
  padding: var(--pj-space-4);
  border-top: 1px solid var(--pj-color-line);
}

.chat-message:first-of-type {
  border-top: 0;
}

.chat-message--self {
  margin-left: auto;
  border-left: 0.2rem solid var(--pj-color-accent);
  background: var(--pj-color-surface-soft);
}

.chat-message > header {
  gap: var(--pj-space-4);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.chat-message > header strong {
  color: var(--pj-color-text);
}

.chat-message p,
.chat-message footer {
  margin: 0;
}

.chat-message > p {
  white-space: pre-wrap;
  line-height: var(--pj-line-height-relaxed);
}

.chat-attachments {
  display: grid;
  gap: var(--pj-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat-attachments li {
  display: grid;
  gap: var(--pj-space-1);
  padding: var(--pj-space-3);
  border-left: 0.2rem solid var(--pj-status-warning-line);
  background: var(--pj-color-surface);
}

.chat-attachments span,
.chat-attachments small {
  color: var(--pj-color-muted);
}

.chat-composer {
  display: grid;
  gap: var(--pj-space-4);
  margin-top: auto;
  padding-block: var(--pj-space-5);
  border-top: 1px solid var(--pj-color-line);
}

.chat-composer textarea {
  resize: vertical;
  line-height: var(--pj-line-height-relaxed);
}

.chat-composer .pj-action-group {
  align-items: center;
}

.chat-closed {
  padding-block: var(--pj-space-5);
  border-top: 1px solid var(--pj-color-line);
  color: var(--pj-color-muted);
}

.chat-thread > .pj-status-notice {
  margin-block: var(--pj-space-4);
}

.chat-thread--empty {
  justify-content: center;
  padding: var(--pj-space-7);
}

@media (max-width: 64rem) {
  .chat-workspace {
    grid-template-columns: minmax(0, 1fr);
  }

  .chat-queue {
    border-right: 0;
    border-bottom: 1px solid var(--pj-color-line);
  }

  .chat-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .chat-list a:nth-child(2) {
    border-top: 1px solid var(--pj-color-line);
  }
}

@media (max-width: 48rem) {
  .chat-hero,
  .chat-panel-header,
  .chat-claim,
  .chat-history-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .chat-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .chat-list {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 32rem) {
  .chat-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .chat-panel-header,
  .chat-facts,
  .chat-claim,
  .chat-history-toolbar,
  .chat-log,
  .chat-composer,
  .chat-closed,
  .chat-thread > .pj-status-notice {
    margin-inline: var(--pj-space-4);
  }

  .chat-list a {
    padding-inline: var(--pj-space-4);
  }

  .chat-thread__status,
  .chat-message > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .chat-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .chat-thread--empty {
    padding: var(--pj-space-5);
  }
}
</style>
