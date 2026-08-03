<script setup lang="ts">
import { computed, watch } from "vue";
import { RouterLink } from "vue-router";

import { formatMoney, type Order } from "@plain-journal/foundation";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  orderStatusPresentation,
  useOrdersStore,
  type OrderAccessContext,
} from "../../entities/order";
import { useSessionStore } from "../../features/customer-session";

const orders = useOrdersStore();
const session = useSessionStore();
const orderAccess = computed<OrderAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

watch(orderAccess, (access) => orders.load(access), {
  immediate: true,
  deep: true,
});

function status(order: Order) {
  return orderStatusPresentation(order);
}

function createdAt(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

async function retryPendingCancellation() {
  const pending = orders.currentAccountPendingCancellation;
  if (pending) {
    await orders.cancelOrder(orderAccess.value, pending.orderNo);
  }
}
</script>

<template>
  <PjPageContainer as="section" class="order-list-page">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>我的订单</span>
    </nav>

    <header class="order-list-header">
      <div>
        <p>购买记录</p>
        <h1>我的订单</h1>
      </div>
      <p>
        {{ orders.total > 0
          ? `已显示 ${orders.orders.length} / ${orders.total} 笔`
          : "订单会按建立时间倒序显示" }}
      </p>
    </header>

    <PjStatusNotice
      v-if="orders.currentAccountPendingCancellation"
      class="order-cancellation-pending"
      tone="unknown"
      title="取消结果待确认"
    >
      <p>
        {{ orders.currentAccountPendingCancellation.orderNo }} ·
        {{ orders.cancellationError ?? "正在以 Trade 订单事实确认取消结果。" }}
      </p>
      <template #actions>
        <PjButton
          variant="secondary"
          :loading="orders.resolvingCancellation || Boolean(orders.cancelingOrderNo)"
          @click="retryPendingCancellation"
        >
          查询并使用同一路径安全重试
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="orders.loading && orders.orders.length === 0"
      tone="processing"
      title="正在读取订单"
    >
      <p>页面正在查询当前账户的订单记录。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="orders.error && orders.orders.length === 0"
      tone="danger"
      title="订单读取未完成"
      assertive
    >
      <p>{{ orders.error }}</p>
      <template #actions>
        <PjButton variant="secondary" @click="orders.load(orderAccess)">
          重新查询订单
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="orders.orders.length === 0"
      tone="neutral"
      title="还没有订单"
    >
      <p>从购物袋进入结算，完成实时价格、库存与优惠核对后才能提交。</p>
      <template #actions>
        <RouterLink class="primary-action" to="/products">浏览商品</RouterLink>
      </template>
    </PjStatusNotice>

    <section v-else class="order-list" aria-label="订单记录">
      <PjStatusNotice
        v-if="orders.error"
        tone="danger"
        title="部分订单刷新未完成"
        assertive
      >
        <p>{{ orders.error }}</p>
      </PjStatusNotice>

      <article v-for="order in orders.orders" :key="order.orderNo" class="order-row">
        <header class="order-row__header">
          <div>
            <time :datetime="order.createdAt">{{ createdAt(order.createdAt) }}</time>
            <h2>{{ order.orderNo }}</h2>
          </div>
          <span
            class="order-row__status"
            :class="`order-row__status--${status(order).tone}`"
          >
            {{ status(order).label }}
          </span>
        </header>

        <div class="order-row__body">
          <div>
            <strong>{{ order.items[0]?.productTitle ?? "订单商品快照" }}</strong>
            <p>
              {{ order.items.length }} 个商品行 ·
              {{ order.items.reduce((total, item) => total + item.quantity, 0) }} 件
            </p>
          </div>
          <div class="order-row__amount">
            <small>订单应付</small>
            <strong>{{ formatMoney(order.totalAmount) }}</strong>
          </div>
        </div>

        <p class="order-row__detail">{{ status(order).detail }}</p>

        <footer class="order-row__footer">
          <span>
            {{ order.status === "PENDING_PAYMENT"
              ? "进入详情后可处理支付；支付进行中与取消保持互斥。"
              : order.status === "CANCELING"
                ? "库存与权益仍在释放，完成前不会显示已取消。"
                : "状态以当前订单记录为准。" }}
          </span>
          <RouterLink
            class="text-action"
            :to="{ name: 'order-detail', params: { orderNo: order.orderNo } }"
          >
            {{ order.status === "PENDING_PAYMENT" ? "查看并可取消" : "查看订单详情" }}
          </RouterLink>
        </footer>
      </article>

      <PjButton
        v-if="orders.hasMore"
        class="order-list__more"
        variant="secondary"
        :loading="orders.loadingMore"
        @click="orders.loadMore(orderAccess)"
      >
        加载更多订单
      </PjButton>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.order-list-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.order-list-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.order-list-header p {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.order-list-header > p {
  margin-bottom: 0;
}

.order-list-header h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
}

.order-cancellation-pending {
  margin-bottom: var(--pj-space-6);
}

.order-list {
  display: grid;
}

.order-row {
  min-width: 0;
  padding-block: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-strong);
}

.order-row:last-of-type {
  border-bottom: 1px solid var(--pj-border-strong);
}

.order-row__header,
.order-row__body,
.order-row__footer {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.order-row__header {
  align-items: start;
}

.order-row__header time {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.order-row__header h2 {
  margin: var(--pj-space-1) 0 0;
  font-size: var(--pj-font-size-md);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.order-row__status {
  flex: 0 0 auto;
  padding-bottom: var(--pj-space-1);
  border-bottom: 0.15rem solid var(--pj-border-strong);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.06em;
}

.order-row__status--active {
  border-color: var(--pj-status-processing-line);
  color: var(--pj-status-processing-text);
}

.order-row__status--success {
  border-color: var(--pj-status-success-line);
  color: var(--pj-status-success-text);
}

.order-row__status--warning {
  border-color: var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
}

.order-row__body {
  align-items: start;
  padding-top: var(--pj-space-5);
}

.order-row__body p,
.order-row__detail,
.order-row__footer {
  color: var(--pj-text-secondary);
}

.order-row__body p,
.order-row__detail {
  margin: var(--pj-space-1) 0 0;
}

.order-row__detail {
  max-width: var(--pj-layout-reading);
}

.order-row__amount {
  flex: 0 0 auto;
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  font-variant-numeric: tabular-nums;
}

.order-row__amount small {
  color: var(--pj-text-secondary);
}

.order-row__amount strong {
  font-size: var(--pj-font-size-lg);
}

.order-row__footer {
  align-items: center;
  margin-top: var(--pj-space-4);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
  font-size: var(--pj-font-size-sm);
}

.order-list__more {
  justify-self: start;
  margin-top: var(--pj-space-6);
}

@media (max-width: 48rem) {
  .order-list-header {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 32rem) {
  .order-list-page {
    padding-top: var(--pj-space-5);
  }

  .order-row__header,
  .order-row__body,
  .order-row__footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .order-row__amount {
    justify-items: start;
  }
}
</style>
