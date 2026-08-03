<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

import type { InAppNotification } from "@plain-journal/foundation";

import {
  NotificationAccessChangedError,
  type NotificationAccessContext,
  useNotificationStore,
} from "../../entities/notification";
import { useSessionStore } from "../../features/customer-session";

const notifications = useNotificationStore();
const session = useSessionStore();
const feedback = ref<string | null>(null);
const accessContext = computed<NotificationAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function notificationTitle(item: InAppNotification): string {
  return {
    PAYMENT_SUCCEEDED: "订单支付成功",
    REFUND_SUCCEEDED: "退款已经完成",
    SHIPMENT_DISPATCHED: "订单已经发货",
    SHIPMENT_SIGNED: "订单已经签收",
  }[item.templateCode] ?? item.title;
}

function notificationContent(item: InAppNotification): string {
  return {
    PAYMENT_SUCCEEDED: `订单 ${item.referenceNo} 已支付成功。`,
    REFUND_SUCCEEDED: `退款 ${item.referenceNo} 已完成，请以订单与支付渠道到账事实为准。`,
    SHIPMENT_DISPATCHED: `订单 ${item.referenceNo} 已发货，可进入订单查看承运与物流轨迹。`,
    SHIPMENT_SIGNED: `订单 ${item.referenceNo} 已记录为签收。`,
  }[item.templateCode] ?? item.content;
}

function referenceLabel(item: InAppNotification): string {
  if (item.referenceType === "ORDER") {
    return `查看订单 ${item.referenceNo}`;
  }
  if (item.referenceType === "REFUND") {
    return `退款编号 ${item.referenceNo}`;
  }
  return `${item.referenceType} ${item.referenceNo}`;
}

async function reload() {
  feedback.value = null;
  try {
    await notifications.load(accessContext.value);
  } catch (cause) {
    if (!(cause instanceof NotificationAccessChangedError)) {
      // The entity exposes the current owner's read failure.
    }
  }
}

async function markRead(notificationId: string) {
  feedback.value = null;
  try {
    if (await notifications.markRead(accessContext.value, notificationId)) {
      feedback.value = "通知已标记为已读。";
    }
  } catch (cause) {
    if (!(cause instanceof NotificationAccessChangedError)) {
      // The entity exposes the current owner's mutation failure.
    }
  }
}

async function reconcileRead() {
  feedback.value = null;
  try {
    const result = await notifications.reconcilePendingRead(accessContext.value);
    if (result === "confirmed-read") {
      feedback.value = "已从通知服务确认这条通知已经读过。";
    }
  } catch (cause) {
    if (!(cause instanceof NotificationAccessChangedError)) {
      // The entity exposes the current owner's reconciliation failure.
    }
  }
}

watch(accessContext, () => {
  void reload();
}, { immediate: true });
</script>

<template>
  <PjPageContainer as="section" size="wide" class="notification-page">
    <nav class="notification-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>通知</span>
    </nav>

    <header class="notification-intro">
      <div>
        <p class="notification-context">账户通知</p>
        <h1>最近发生的事项</h1>
      </div>
      <p>{{ notifications.unreadCount }} 条未读</p>
    </header>

    <PjStatusNotice
      v-if="feedback"
      class="notification-feedback"
      tone="success"
      title="通知事实已更新"
    >
      <p>{{ feedback }}</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="notifications.error"
      class="notification-error"
      tone="danger"
      title="通知事实未能读取"
      assertive
    >
      <p>{{ notifications.error }}</p>
      <template #actions>
        <PjButton variant="text" @click="reload">重新读取通知</PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="notifications.readError"
      class="notification-read-result"
      :tone="notifications.readUnknown ? 'unknown' : 'warning'"
      :title="notifications.readUnknown ? '已读结果待确认' : '已读状态需要核对'"
    >
      <p>{{ notifications.readError }}</p>
      <template v-if="notifications.readUnknown" #actions>
        <PjButton variant="text" @click="reconcileRead">
          重新读取通知事实
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="notifications.loading && notifications.notifications.length === 0"
      tone="processing"
      title="正在读取当前账户的通知"
    >
      <p>站内信由 Notification 的 MySQL 事实生成，不以邮件是否送达为准。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="!notifications.error && notifications.notifications.length === 0"
      tone="neutral"
      title="当前没有通知"
    >
      <p>支付、退款、发货或签收发生后，新的站内通知会出现在这里。</p>
    </PjStatusNotice>

    <section
      v-else-if="notifications.notifications.length > 0"
      class="notification-list"
      aria-label="当前账户通知列表"
    >
      <article
        v-for="item in notifications.notifications"
        :key="item.id"
        class="notification-row"
        :class="{ 'notification-row--unread': item.status === 'UNREAD' }"
      >
        <div class="notification-row__time">
          <span>{{ formatTimestamp(item.createdAt) }}</span>
          <strong>{{ item.status === "UNREAD" ? "未读" : "已读" }}</strong>
        </div>
        <div class="notification-row__content">
          <p class="notification-context">{{ item.templateCode }}</p>
          <h2>{{ notificationTitle(item) }}</h2>
          <p>{{ notificationContent(item) }}</p>
          <RouterLink
            v-if="item.referenceType === 'ORDER'"
            :to="{ name: 'order-detail', params: { orderNo: item.referenceNo } }"
          >
            {{ referenceLabel(item) }} →
          </RouterLink>
          <span v-else class="notification-reference">
            {{ referenceLabel(item) }}
          </span>
        </div>
        <PjButton
          v-if="item.status === 'UNREAD'"
          variant="text"
          :loading="notifications.markingReadId === item.id"
          @click="markRead(item.id)"
        >
          {{
            notifications.markingReadId === item.id
              ? "正在确认…"
              : "标记为已读"
          }}
        </PjButton>
      </article>

      <PjButton
        v-if="notifications.hasMore"
        class="notification-load-more"
        variant="text"
        :loading="notifications.loadingOlder"
        @click="notifications.load(accessContext, { append: true })"
      >
        {{ notifications.loadingOlder ? "正在读取…" : "读取更早通知" }}
      </PjButton>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.notification-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.notification-path {
  display: flex;
  gap: var(--pj-space-2);
  margin-bottom: var(--pj-space-7);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.notification-intro {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.notification-intro h1 {
  max-width: 16ch;
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
  line-height: var(--pj-line-height-tight);
}

.notification-intro > p,
.notification-context,
.notification-row__time,
.notification-reference {
  color: var(--pj-text-secondary);
}

.notification-context {
  margin: 0 0 var(--pj-space-2);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.notification-feedback,
.notification-error,
.notification-read-result {
  margin-bottom: var(--pj-space-5);
}

.notification-list {
  border-top: 1px solid var(--pj-border-strong);
}

.notification-row {
  display: grid;
  grid-template-columns: minmax(8rem, 0.25fr) minmax(0, 1fr) auto;
  gap: var(--pj-space-6);
  align-items: start;
  padding-block: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.notification-row--unread {
  border-left: 0.2rem solid var(--pj-brand-primary);
  padding-left: var(--pj-space-4);
}

.notification-row__time {
  display: grid;
  gap: var(--pj-space-2);
  font-size: var(--pj-font-size-sm);
}

.notification-row__time strong {
  color: var(--pj-text-primary);
  font-size: var(--pj-font-size-xs);
  letter-spacing: 0.08em;
}

.notification-row__content {
  min-width: 0;
}

.notification-row__content h2,
.notification-row__content > p {
  margin: 0;
}

.notification-row__content h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
}

.notification-row__content > p:not(.notification-context) {
  max-width: var(--pj-layout-reading);
  margin-top: var(--pj-space-3);
  color: var(--pj-text-secondary);
}

.notification-row__content a,
.notification-reference {
  display: inline-block;
  margin-top: var(--pj-space-4);
}

.notification-load-more {
  margin-top: var(--pj-space-5);
}

@media (max-width: 48rem) {
  .notification-intro {
    align-items: flex-start;
    flex-direction: column;
  }

  .notification-row {
    grid-template-columns: 1fr auto;
    gap: var(--pj-space-4);
  }

  .notification-row__time {
    grid-column: 1 / -1;
    display: flex;
  }
}

@media (max-width: 32rem) {
  .notification-row {
    grid-template-columns: 1fr;
  }
}
</style>
