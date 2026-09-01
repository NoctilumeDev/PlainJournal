<script setup lang="ts">
import { computed, watch } from "vue";
import { RouterLink } from "vue-router";

import { formatMoney } from "@plain-journal/foundation";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  afterSaleStatusPresentation,
  useAfterSalesStore,
  type AfterSaleAccessContext,
} from "../entities/after-sale";
import { useSessionStore } from "../features/customer-session";

const store = useAfterSalesStore();
const session = useSessionStore();
const access = computed<AfterSaleAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

watch(access, (value) => store.load(value), { immediate: true, deep: true });
</script>

<template>
  <PjPageContainer as="section" class="after-sale-list-page">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>售后服务</span>
    </nav>

    <header class="after-sale-list-header">
      <div>
        <p>退货与退款记录</p>
        <h1>售后服务</h1>
      </div>
      <PjButton
        variant="text"
        :loading="store.loading"
        @click="store.load(access)"
      >
        {{ store.loading ? "正在刷新…" : "刷新" }}
      </PjButton>
    </header>

    <PjStatusNotice
      v-if="store.loading && store.afterSales.length === 0"
      tone="processing"
      title="正在读取售后记录"
    >
      <p>正在查询当前账户的售后事实…</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="store.error && store.afterSales.length === 0"
      tone="danger"
      title="售后记录读取未完成"
      assertive
    >
      <p>{{ store.error }}</p>
      <template #actions>
        <PjButton variant="secondary" @click="store.load(access)">
          重新查询
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="store.afterSales.length === 0"
      tone="neutral"
      title="当前账户还没有售后申请"
    >
      <p>已完成订单可在订单详情中申请首版整单退货退款。</p>
      <template #actions>
        <RouterLink class="text-action" to="/orders">查看我的订单</RouterLink>
      </template>
    </PjStatusNotice>

    <section v-else class="after-sale-list" aria-label="售后记录">
      <PjStatusNotice
        v-if="store.error"
        tone="danger"
        title="部分售后记录刷新未完成"
        assertive
      >
        <p>{{ store.error }}</p>
      </PjStatusNotice>

      <article
        v-for="afterSale in store.afterSales"
        :key="afterSale.afterSaleNo"
        class="after-sale-row"
      >
        <header class="after-sale-row__header">
          <div>
            <time :datetime="afterSale.createdAt">
              {{ formatTimestamp(afterSale.createdAt) }}
            </time>
            <h2>订单 {{ afterSale.orderNo }}</h2>
            <p>{{ afterSale.afterSaleNo }}</p>
          </div>
          <span
            class="after-sale-row__status"
            :class="`after-sale-row__status--${afterSaleStatusPresentation(afterSale).tone}`"
          >
            {{ afterSaleStatusPresentation(afterSale).label }}
          </span>
        </header>

        <p class="after-sale-row__detail">
          {{ afterSaleStatusPresentation(afterSale).detail }}
        </p>

        <dl class="after-sale-row__facts">
          <div>
            <dt>退款金额</dt>
            <dd>{{ formatMoney(afterSale.refundAmount) }}</dd>
          </div>
          <div>
            <dt>当前处理方</dt>
            <dd>{{ afterSaleStatusPresentation(afterSale).owner }}</dd>
          </div>
          <div>
            <dt>退货单</dt>
            <dd>{{ afterSale.returnReceiptNo || "尚未建立" }}</dd>
          </div>
        </dl>

        <footer class="after-sale-row__footer">
          <span>{{ afterSaleStatusPresentation(afterSale).nextAction }}</span>
          <RouterLink
            class="text-action"
            :aria-label="`查看订单 ${afterSale.orderNo} 的售后详情`"
            :to="{
              name: 'after-sale-detail',
              params: { afterSaleNo: afterSale.afterSaleNo },
            }"
          >
            查看售后详情
          </RouterLink>
        </footer>
      </article>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.after-sale-list-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.after-sale-list-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.after-sale-list-header p {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.after-sale-list-header h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.after-sale-list {
  display: grid;
}

.after-sale-row {
  min-width: 0;
  padding-block: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-strong);
}

.after-sale-row:last-of-type {
  border-bottom: 1px solid var(--pj-border-strong);
}

.after-sale-row__header,
.after-sale-row__footer {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.after-sale-row__header {
  align-items: start;
}

.after-sale-row__header time,
.after-sale-row__header p {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.after-sale-row__header h2 {
  margin: var(--pj-space-1) 0;
  font-size: var(--pj-font-size-md);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.after-sale-row__header p {
  margin: 0;
  overflow-wrap: anywhere;
}

.after-sale-row__status {
  flex: 0 0 auto;
  padding-bottom: var(--pj-space-1);
  border-bottom: 0.15rem solid var(--pj-border-strong);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.06em;
}

.after-sale-row__status--processing {
  border-color: var(--pj-status-processing-line);
  color: var(--pj-status-processing-text);
}

.after-sale-row__status--warning {
  border-color: var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
}

.after-sale-row__status--attention {
  border-color: var(--pj-status-attention-line);
  color: var(--pj-status-attention-text);
}

.after-sale-row__status--success {
  border-color: var(--pj-status-success-line);
  color: var(--pj-status-success-text);
}

.after-sale-row__detail {
  max-width: var(--pj-layout-reading);
  margin: var(--pj-space-4) 0 0;
  color: var(--pj-text-secondary);
}

.after-sale-row__facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: var(--pj-space-5) 0 0;
}

.after-sale-row__facts div {
  min-width: 0;
}

.after-sale-row__facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.after-sale-row__facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.after-sale-row__footer {
  align-items: center;
  margin-top: var(--pj-space-4);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

@media (max-width: 48rem) {
  .after-sale-list-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .after-sale-row__facts {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 32rem) {
  .after-sale-list-page {
    padding-top: var(--pj-space-5);
  }

  .after-sale-row__header,
  .after-sale-row__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
