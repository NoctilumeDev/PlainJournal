<script setup lang="ts">
import { computed, onMounted, watch } from "vue";
import { RouterLink } from "vue-router";

import {
  formatMoney,
  type BusinessId,
} from "@plain-journal/foundation";
import {
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import { useAdminAnalyticsStore } from "../entities/admin-analytics";
import { useStaffSessionStore } from "../stores/session";

interface WorkspaceEntry {
  to: string;
  title: string;
  description: string;
  roles: string[];
}

const workspaceEntries: WorkspaceEntry[] = [
  {
    to: "/catalog",
    title: "商品目录",
    description: "查看当前公开 ACTIVE 商品投影与真实分页。",
    roles: ["ADMIN", "OPERATOR"],
  },
  {
    to: "/inventory",
    title: "库存",
    description: "核对仓库库存事实，处理幂等库存调整。",
    roles: ["ADMIN", "WAREHOUSE"],
  },
  {
    to: "/fulfillment",
    title: "履约与退货",
    description: "推进正向履约、物流轨迹、异常与退货验收。",
    roles: ["ADMIN", "WAREHOUSE"],
  },
  {
    to: "/after-sales",
    title: "售后审核",
    description: "依据整单快照审核退货退款申请。",
    roles: ["ADMIN"],
  },
  {
    to: "/marketing",
    title: "营销权益",
    description: "维护优惠规则并以稳定发放键授予权益。",
    roles: ["ADMIN", "OPERATOR"],
  },
  {
    to: "/chat",
    title: "客服会话",
    description: "在成员授权后读取私聊正文并回复顾客。",
    roles: ["ADMIN", "OPERATOR"],
  },
  {
    to: "/reviews",
    title: "评价治理",
    description: "回复公开评价并记录举报审核事实。",
    roles: ["ADMIN", "OPERATOR"],
  },
  {
    to: "/governance",
    title: "补偿与对账",
    description: "读取四域偏差，执行有审计的授权恢复。",
    roles: ["ADMIN"],
  },
];

const session = useStaffSessionStore();
const analytics = useAdminAnalyticsStore();
const roles = computed(() => session.profile?.roles ?? []);
const canReadAnalytics = computed(() =>
  roles.value.some((role) => role === "ADMIN" || role === "OPERATOR"));
const accessContext = computed(() => ({
  authorized: Boolean(session.authenticated && canReadAnalytics.value),
  operatorId: (session.profile?.id as BusinessId | undefined) ?? null,
  accessToken: session.accessToken,
}));
const visibleWorkspaces = computed(() =>
  workspaceEntries.filter((entry) =>
    roles.value.some((role) => entry.roles.includes(role))));
const metricFacts = computed(() => {
  const totals = analytics.dashboard?.totals;
  if (!totals) {
    return [];
  }
  return [
    {
      label: "创建订单",
      value: totals.createdOrderCount,
      detail: formatMoney(totals.createdOrderAmount),
    },
    {
      label: "支付成功",
      value: totals.paymentCount,
      detail: formatMoney(totals.paymentAmount),
    },
    {
      label: "完成订单",
      value: totals.completedOrderCount,
      detail: formatMoney(totals.completedOrderAmount),
    },
    {
      label: "关闭订单",
      value: totals.closedOrderCount,
      detail: "仅表示订单关闭事实",
    },
    {
      label: "售后申请",
      value: totals.afterSaleCount,
      detail: formatMoney(totals.afterSaleAmount),
    },
    {
      label: "退款成功",
      value: totals.refundCount,
      detail: formatMoney(totals.refundAmount),
    },
  ];
});

function formatTimestamp(value: string | null): string {
  return value
    ? new Date(value).toLocaleString("zh-CN", {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "尚未消费事件";
}

function formatBusinessDate(value: string): string {
  return new Date(`${value}T00:00:00Z`).toLocaleDateString("zh-CN", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

function loadAnalytics() {
  return analytics.load(accessContext.value);
}

watch(accessContext, (context, previous) => {
  analytics.synchronizeAccess(context);
  if (
    context.authorized
    && (
      !previous?.authorized
      || context.operatorId !== previous.operatorId
      || context.accessToken !== previous.accessToken
    )
  ) {
    void analytics.load(context);
  }
});

onMounted(() => {
  analytics.synchronizeAccess(accessContext.value);
  if (accessContext.value.authorized) {
    void analytics.load(accessContext.value);
  }
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="operations-page">
    <header class="operations-hero">
      <div>
        <p class="eyebrow">权限内工作概览</p>
        <h1>工作区</h1>
        <p>
          从运营投影了解最近变化，再进入拥有权限的事实工作区。首页不跨服务拼接
          “实时真相”，也不替代订单、资金、库存和履约所有者域。
        </p>
      </div>
      <span class="status-label">{{ roles.join(" / ") }}</span>
    </header>

    <section class="workspace-index" aria-labelledby="workspace-index-title">
      <header>
        <div>
          <p class="eyebrow">当前员工可用</p>
          <h2 id="workspace-index-title">事实工作区</h2>
        </div>
        <span>{{ visibleWorkspaces.length }} 个入口</span>
      </header>
      <nav class="workspace-index__list" aria-label="当前员工可用工作区">
        <RouterLink
          v-for="entry in visibleWorkspaces"
          :key="entry.to"
          :to="entry.to"
        >
          <span>
            <strong>{{ entry.title }}</strong>
            <small>{{ entry.description }}</small>
          </span>
          <span aria-hidden="true">进入 →</span>
        </RouterLink>
      </nav>
    </section>

    <section
      v-if="canReadAnalytics"
      class="analytics-section"
      aria-labelledby="analytics-title"
    >
      <header class="analytics-section__header">
        <div>
          <p class="eyebrow">Analytics 事件读模型</p>
          <h2 id="analytics-title">运营投影</h2>
          <p>
            统计由版本化事件构建，可重建、可能滞后。交易域 MySQL 仍是订单、支付、
            售后和退款的最终事实。
          </p>
        </div>
        <span class="status-label">只读投影</span>
      </header>

      <PjSurface tone="soft" padding="large">
        <form class="analytics-range" @submit.prevent="loadAnalytics">
          <PjField
            v-slot="{ describedBy }"
            label="开始日期"
            for-id="analytics-from"
            hint="单次最多读取 366 天。"
          >
            <input
              id="analytics-from"
              v-model="analytics.range.from"
              class="pj-control"
              type="date"
              :max="analytics.range.to"
              :aria-describedby="describedBy"
              required
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="结束日期"
            for-id="analytics-to"
          >
            <input
              id="analytics-to"
              v-model="analytics.range.to"
              class="pj-control"
              type="date"
              :min="analytics.range.from"
              :aria-describedby="describedBy"
              required
            />
          </PjField>
          <PjButton type="submit" :loading="analytics.loading">
            读取运营投影
          </PjButton>
        </form>
      </PjSurface>

      <PjStatusNotice
        v-if="analytics.error && analytics.hasKnownProjection"
        tone="warning"
        title="本次刷新未确认"
      >
        <p>
          {{ analytics.error }} 页面继续展示上一次已知投影，并明确保留其原生成时间。
        </p>
      </PjStatusNotice>
      <PjStatusNotice
        v-else-if="analytics.error"
        tone="danger"
        title="运营投影读取未完成"
        assertive
      >
        <p>{{ analytics.error }}</p>
      </PjStatusNotice>

      <div
        v-if="analytics.loading && !analytics.dashboard"
        class="analytics-state"
        role="status"
      >
        正在读取 Analytics 本地投影…
      </div>

      <template v-else-if="analytics.dashboard">
        <section class="analytics-facts" aria-labelledby="analytics-facts-title">
          <header>
            <div>
              <p class="eyebrow">所选区间</p>
              <h3 id="analytics-facts-title">
                {{ analytics.dashboard.from }} 至 {{ analytics.dashboard.to }}
              </h3>
            </div>
            <span>
              {{ analytics.loading ? "正在刷新" : "读取完成" }}
            </span>
          </header>
          <dl class="analytics-metrics">
            <div v-for="metric in metricFacts" :key="metric.label">
              <dt>{{ metric.label }}</dt>
              <dd class="analytics-metrics__value">{{ metric.value }}</dd>
              <dd class="analytics-metrics__detail">{{ metric.detail }}</dd>
            </div>
          </dl>
        </section>

        <div class="analytics-detail-grid">
          <PjSurface as="article" padding="large" class="analytics-products">
            <header>
              <div>
                <p class="eyebrow">完成订单商品快照</p>
                <h3>商品贡献</h3>
              </div>
              <span>{{ analytics.dashboard.topProducts.length }} 项</span>
            </header>
            <p
              v-if="analytics.dashboard.topProducts.length === 0"
              class="analytics-empty"
            >
              当前区间没有已完成订单商品。
            </p>
            <ol v-else>
              <li
                v-for="product in analytics.dashboard.topProducts"
                :key="product.productId"
              >
                <div>
                  <strong>{{ product.productTitle }}</strong>
                  <small>商品 ID {{ product.productId }}</small>
                  <span>
                    {{ product.completedOrderCount }} 单 ·
                    {{ product.unitsSold }} 件
                  </span>
                </div>
                <div class="analytics-products__amount">
                  <strong>{{ formatMoney(product.netRevenue) }}</strong>
                  <small>
                    收入覆盖 {{ product.revenueCoveredOrderCount }}
                    / {{ product.completedOrderCount }} 单
                  </small>
                </div>
              </li>
            </ol>
          </PjSurface>

          <PjSurface as="article" tone="soft" padding="large" class="analytics-freshness">
            <p class="eyebrow">投影边界</p>
            <h3>新鲜度与覆盖</h3>
            <dl>
              <div>
                <dt>来源事件</dt>
                <dd>{{ analytics.dashboard.freshness.sourceEventCount }}</dd>
              </div>
              <div>
                <dt>独立顾客</dt>
                <dd>{{ analytics.dashboard.totals.uniqueCustomers }}</dd>
              </div>
              <div>
                <dt>最后消费</dt>
                <dd>
                  {{ formatTimestamp(analytics.dashboard.freshness.lastConsumedAt) }}
                </dd>
              </div>
              <div>
                <dt>投影生成</dt>
                <dd>
                  {{ formatTimestamp(analytics.dashboard.freshness.generatedAt) }}
                </dd>
              </div>
            </dl>
          </PjSurface>
        </div>

        <PjSurface
          v-if="analytics.recentDaily.length > 0"
          as="section"
          padding="large"
          class="analytics-daily"
          aria-labelledby="analytics-daily-title"
        >
          <header>
            <div>
              <p class="eyebrow">最近七个有事实日期</p>
              <h3 id="analytics-daily-title">每日脉络</h3>
            </div>
            <span>不是实时趋势线</span>
          </header>
          <ol>
            <li
              v-for="summary in analytics.recentDaily"
              :key="summary.businessDate"
            >
              <strong>{{ formatBusinessDate(summary.businessDate) }}</strong>
              <span>创建 {{ summary.createdOrderCount }}</span>
              <span>支付 {{ summary.paymentCount }}</span>
              <span>完成 {{ summary.completedOrderCount }}</span>
              <span>退款 {{ summary.refundCount }}</span>
            </li>
          </ol>
        </PjSurface>
      </template>
    </section>

    <PjStatusNotice
      v-else
      tone="neutral"
      title="运营统计按角色隔离"
    >
      <p>
        WAREHOUSE 角色可以进入库存与履约工作区，但不能读取平台运营统计。
        登录成功不等于获得跨领域数据权限。
      </p>
    </PjStatusNotice>
  </PjPageContainer>
</template>

<style scoped>
.operations-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7);
}

.operations-hero,
.workspace-index > header,
.analytics-section__header,
.analytics-facts > header,
.analytics-products > header,
.analytics-daily > header {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.operations-hero h1 {
  margin: 0;
  font-size: clamp(2.2rem, 6vw, 4.8rem);
  font-weight: 500;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.operations-hero p:last-child,
.analytics-section__header p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.workspace-index,
.analytics-section,
.analytics-facts {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.workspace-index h2,
.analytics-section h2,
.analytics-facts h3,
.analytics-detail-grid h3,
.analytics-daily h3 {
  margin: var(--pj-space-1) 0 0;
}

.workspace-index > header > span,
.analytics-facts > header > span,
.analytics-products > header > span,
.analytics-daily > header > span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.workspace-index__list {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--pj-border-subtle);
}

.workspace-index__list a {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding: var(--pj-space-5) 0;
  border-bottom: 1px solid var(--pj-border-subtle);
  color: inherit;
  text-decoration: none;
}

.workspace-index__list a:nth-child(odd) {
  padding-right: var(--pj-space-5);
}

.workspace-index__list a:nth-child(even) {
  padding-left: var(--pj-space-5);
  border-left: 1px solid var(--pj-border-subtle);
}

.workspace-index__list a:hover strong,
.workspace-index__list a:focus-visible strong {
  color: var(--pj-brand-primary);
}

.workspace-index__list a > span:first-child {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-2);
}

.workspace-index__list small {
  color: var(--pj-text-secondary);
  line-height: 1.6;
}

.workspace-index__list a > span:last-child {
  flex: 0 0 auto;
  color: var(--pj-brand-primary);
  font-size: var(--pj-font-size-sm);
}

.analytics-range {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(10rem, 1fr)) auto;
  align-items: end;
  gap: var(--pj-space-5);
}

.analytics-state {
  min-height: 10rem;
  display: grid;
  place-items: center;
  border-block: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
}

.analytics-metrics {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  border-top: 1px solid var(--pj-border-subtle);
  border-left: 1px solid var(--pj-border-subtle);
}

.analytics-metrics > div {
  min-width: 0;
  padding: var(--pj-space-5);
  border-right: 1px solid var(--pj-border-subtle);
  border-bottom: 1px solid var(--pj-border-subtle);
  background: var(--pj-color-surface);
}

.analytics-metrics dt,
.analytics-metrics__detail,
.analytics-products small,
.analytics-products span,
.analytics-freshness dt,
.analytics-empty {
  color: var(--pj-text-secondary);
}

.analytics-metrics__value {
  margin: var(--pj-space-3) 0 var(--pj-space-2);
  font-size: clamp(1.8rem, 5vw, 2.8rem);
  line-height: 1;
}

.analytics-metrics__detail {
  margin: 0;
  font-size: var(--pj-font-size-sm);
}

.analytics-detail-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(17rem, 0.6fr);
  gap: var(--pj-space-4);
}

.analytics-products ol,
.analytics-daily ol {
  display: grid;
  margin: var(--pj-space-5) 0 0;
  padding: 0;
  list-style: none;
}

.analytics-products li {
  min-width: 0;
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding: var(--pj-space-4) 0;
  border-top: 1px solid var(--pj-border-subtle);
}

.analytics-products li > div {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
}

.analytics-products li strong,
.analytics-products li small {
  overflow-wrap: anywhere;
}

.analytics-products__amount {
  flex: 0 0 auto;
  text-align: right;
}

.analytics-freshness dl {
  display: grid;
  gap: var(--pj-space-4);
  margin: var(--pj-space-5) 0 0;
}

.analytics-freshness dl > div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.analytics-freshness dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.analytics-daily ol {
  border-top: 1px solid var(--pj-border-subtle);
}

.analytics-daily li {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(6rem, 1fr) repeat(4, minmax(4rem, 0.55fr));
  gap: var(--pj-space-3);
  padding: var(--pj-space-3) 0;
  border-bottom: 1px solid var(--pj-border-subtle);
}

.analytics-daily li span {
  color: var(--pj-text-secondary);
}

@media (max-width: 64rem) {
  .analytics-detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 52rem) {
  .analytics-range,
  .analytics-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .analytics-range > .pj-button {
    grid-column: 1 / -1;
  }

  .analytics-daily li {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .analytics-daily li strong {
    grid-column: 1 / -1;
  }
}

@media (max-width: 48rem) {
  .operations-hero,
  .workspace-index > header,
  .analytics-section__header,
  .analytics-facts > header,
  .analytics-products > header,
  .analytics-daily > header {
    flex-direction: column;
  }

  .workspace-index__list,
  .analytics-range,
  .analytics-metrics {
    grid-template-columns: minmax(0, 1fr);
  }

  .workspace-index__list a:nth-child(odd),
  .workspace-index__list a:nth-child(even) {
    padding-inline: 0;
    border-left: 0;
  }

  .analytics-range > .pj-button {
    grid-column: auto;
  }

  .analytics-products li {
    flex-direction: column;
  }

  .analytics-products__amount {
    text-align: left;
  }
}

@media (max-width: 32rem) {
  .operations-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .workspace-index__list a {
    flex-direction: column;
  }
}
</style>
