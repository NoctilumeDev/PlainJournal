<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, useRouter } from "vue-router";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  type BagMergeStatus,
  useSessionStore,
} from "../../features/customer-session";

const router = useRouter();
const session = useSessionStore();

type StatusTone =
  | "success"
  | "processing"
  | "unknown"
  | "danger"
  | "attention";

const mergePresentations: Record<
  Exclude<BagMergeStatus, "idle">,
  { tone: StatusTone; title: string }
> = {
  pending: {
    tone: "processing",
    title: "正在合并购物袋",
  },
  succeeded: {
    tone: "success",
    title: "购物袋已合并",
  },
  unknown: {
    tone: "unknown",
    title: "合并结果待确认",
  },
  failed: {
    tone: "danger",
    title: "购物袋合并未完成",
  },
  "ownership-conflict": {
    tone: "attention",
    title: "需要先核对设备上的待确认合并",
  },
};

const mergePresentation = computed(() => session.bagMergeStatus === "idle"
  ? null
  : mergePresentations[session.bagMergeStatus]);
const accountStatus = computed(() => session.profile?.status === "ACTIVE"
  ? "使用中"
  : session.profile?.status);
const accountRoles = computed(() => session.profile?.roles.map((role) => {
  if (role === "CUSTOMER") {
    return "顾客";
  }
  if (role === "ADMIN") {
    return "管理员";
  }
  return role;
}).join(" / "));

async function logout() {
  try {
    await session.logout();
    await router.replace("/");
  } catch {
    // Keep the session until the user explicitly chooses the local-only boundary.
  }
}

async function clearLocal() {
  session.clearLocalOnly();
  await router.replace("/");
}
</script>

<template>
  <PjPageContainer as="section" size="reading" class="account-page">
    <PjSurface as="section" tone="plain" padding="medium" class="account-profile">
      <header class="account-header">
        <div>
          <p class="account-context">账户</p>
          <h1>{{ session.profile?.displayName }}</h1>
        </div>
        <span class="account-status">{{ accountStatus }}</span>
      </header>

      <dl class="account-facts">
        <div>
          <dt>邮箱</dt>
          <dd>{{ session.profile?.email }}</dd>
        </div>
        <div>
          <dt>账户 ID</dt>
          <dd><code>{{ session.profile?.id }}</code></dd>
        </div>
        <div>
          <dt>身份</dt>
          <dd>{{ accountRoles }}</dd>
        </div>
      </dl>
    </PjSurface>

    <PjStatusNotice
      v-if="session.bagMergeMessage && mergePresentation"
      class="account-merge-notice"
      :tone="mergePresentation.tone"
      :title="mergePresentation.title"
    >
      <p>{{ session.bagMergeMessage }}</p>
      <template
        v-if="['unknown', 'failed'].includes(session.bagMergeStatus)"
        #actions
      >
        <PjButton variant="text" @click="session.mergeGuestBag">
          使用原重试键再次确认
        </PjButton>
      </template>
    </PjStatusNotice>

    <section class="account-links" aria-labelledby="account-tools-title">
      <header>
        <p class="account-context">购买与服务</p>
        <h2 id="account-tools-title">从账户继续处理</h2>
      </header>
      <RouterLink to="/account/addresses">
        <span>
          <strong>收货信息</strong>
          <small>新增、修改、设为默认或删除地址</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/orders">
        <span>
          <strong>我的订单</strong>
          <small>查看订单状态、购买快照与取消进度</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/after-sales">
        <span>
          <strong>售后服务</strong>
          <small>查看整单退货、寄回验收与退款进度</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/account/benefits">
        <span>
          <strong>优惠权益</strong>
          <small>查看优惠的可用、锁定与使用记录</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/account/notifications">
        <span>
          <strong>通知</strong>
          <small>查看支付、退款、发货与签收事项</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/support">
        <span>
          <strong>联系素简记</strong>
          <small>查看客服会话、消息历史与实时回复</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/bag">
        <span>
          <strong>账户购物车</strong>
          <small>查看已登录账户保存的购物车商品</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
      <RouterLink to="/checkout">
        <span>
          <strong>结算草稿</strong>
          <small>核对地址、商品、优惠与最终应付金额</small>
        </span>
        <span aria-hidden="true">→</span>
      </RouterLink>
    </section>

    <PjSurface as="section" tone="soft" padding="medium" class="account-actions">
      <div>
        <h2>退出当前设备</h2>
        <p>先请求服务端撤销刷新令牌；只有确认成功后，才会清除本机会话。</p>
      </div>
      <PjButton
        variant="text"
        :disabled="session.busy"
        :loading="session.busy"
        @click="logout"
      >
        {{ session.busy ? "正在退出…" : "退出" }}
      </PjButton>
    </PjSurface>

    <PjStatusNotice
      v-if="session.logoutError"
      class="account-logout-notice"
      tone="unknown"
      title="服务端退出结果待确认"
    >
      <p>{{ session.logoutError }}</p>
      <p>服务端撤销结果未知。你可以保留会话稍后重试，或只清除此设备。</p>
      <template #actions>
        <PjButton variant="destructive" @click="clearLocal">
          仅清除此设备
        </PjButton>
      </template>
    </PjStatusNotice>
  </PjPageContainer>
</template>

<style scoped>
.account-page {
  padding-block: var(--pj-space-8);
}

.account-context {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.account-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-bottom: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-text-primary);
}

.account-header h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: var(--pj-line-height-tight);
}

.account-status {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
  letter-spacing: 0.08em;
}

.account-facts {
  margin: 0;
}

.account-facts > div {
  display: grid;
  grid-template-columns: minmax(8rem, 0.4fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.account-facts dt,
.account-actions p {
  color: var(--pj-text-secondary);
}

.account-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.account-merge-notice,
.account-logout-notice,
.account-actions {
  margin-top: var(--pj-space-7);
}

.account-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-6);
}

.account-actions h2,
.account-actions p {
  margin: 0;
}

.account-actions p {
  margin-top: var(--pj-space-2);
}

.account-links {
  margin-top: var(--pj-space-7);
}

.account-links header {
  padding-bottom: var(--pj-space-4);
}

.account-links h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 550;
}

.account-links > a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
  text-decoration: none;
}

.account-links > a:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.account-links > a span:first-child {
  display: grid;
  gap: var(--pj-space-2);
}

.account-links small {
  color: var(--pj-text-secondary);
}

@media (max-width: 48rem) {
  .account-facts > div {
    grid-template-columns: 1fr;
    gap: var(--pj-space-1);
  }
}

@media (max-width: 32rem) {
  .account-header,
  .account-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
