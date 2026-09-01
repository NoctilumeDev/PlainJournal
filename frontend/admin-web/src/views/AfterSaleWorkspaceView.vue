<script setup lang="ts">
import { computed, onMounted, watch } from "vue";

import {
  formatMoney,
  type AfterSale,
} from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  useAdminAfterSaleStore,
  type AfterSaleReviewPhase,
} from "../entities/admin-after-sale";
import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
const afterSale = useAdminAfterSaleStore();
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.includes("ADMIN"),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const statusOptions = [
  { value: "", label: "全部状态" },
  { value: "APPLIED", label: "等待审核" },
  { value: "WAIT_RETURN", label: "等待顾客寄回" },
  { value: "RETURNING", label: "退货运输中" },
  { value: "RECEIVED", label: "仓库已收货" },
  { value: "REFUNDING", label: "退款处理中" },
  { value: "REFUND_FAILED", label: "退款需要治理" },
  { value: "COMPLETED", label: "售后已完成" },
  { value: "REJECTED", label: "审核已拒绝" },
  { value: "CANCELED", label: "顾客已撤销" },
];

const statusPresentation: Record<string, {
  label: string;
  owner: string;
  next: string;
  tone: string;
}> = {
  APPLIED: {
    label: "等待审核",
    owner: "Trade 管理审核",
    next: "核对整单退款快照，并记录明确的通过或拒绝原因。",
    tone: "warning",
  },
  WAIT_RETURN: {
    label: "等待寄回",
    owner: "顾客 / Fulfillment",
    next: "等待退货单建立及顾客提交承运商和运单号。",
    tone: "processing",
  },
  RETURNING: {
    label: "退货途中",
    owner: "Fulfillment",
    next: "仓库收货后继续验收，不由 Trade 页面提前判断。",
    tone: "processing",
  },
  RECEIVED: {
    label: "仓库已收货",
    owner: "Fulfillment / Inventory",
    next: "等待验收与库存回补事实推进退款申请。",
    tone: "processing",
  },
  REFUNDING: {
    label: "退款处理中",
    owner: "Payment",
    next: "等待渠道退款结果；处理中不等于退款到账。",
    tone: "processing",
  },
  REFUND_FAILED: {
    label: "退款需要治理",
    owner: "Payment 治理",
    next: "只能通过授权补偿与审计恢复，不能直接改成成功。",
    tone: "attention",
  },
  COMPLETED: {
    label: "售后完成",
    owner: "Trade 最终事实",
    next: "退款成功事件已经推进售后终态。",
    tone: "success",
  },
  REJECTED: {
    label: "审核拒绝",
    owner: "Trade 管理审核",
    next: "拒绝原因保留为售后历史事实。",
    tone: "danger",
  },
  CANCELED: {
    label: "顾客撤销",
    owner: "Trade 顾客命令",
    next: "申请在审核前撤销，当前售后已经终止。",
    tone: "neutral",
  },
};

function presentation(value: AfterSale) {
  return statusPresentation[value.status] ?? {
    label: value.status,
    owner: "Trade",
    next: "等待所有者域给出下一条明确事实。",
    tone: "neutral",
  };
}

function noticeTone(phase: AfterSaleReviewPhase) {
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

function noticeTitle(phase: AfterSaleReviewPhase) {
  return {
    idle: "售后审核",
    processing: "审核正在处理中",
    unknown: "审核结果未知",
    accepted: "审核结果已确认",
    rejected: "审核未被接受",
  }[phase];
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString("zh-CN") : "—";
}

function fieldId(prefix: string, referenceNo: string) {
  return `${prefix}-${referenceNo.replace(/[^A-Za-z0-9_-]/gu, "-")}`;
}

function loadFacts() {
  return afterSale.loadFacts(accessContext.value);
}

watch(accessContext, (context) => {
  afterSale.synchronizeAccess(context);
});

onMounted(() => {
  afterSale.synchronizeAccess(accessContext.value);
  void loadFacts();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="after-sale-page">
    <header class="after-sale-hero">
      <div>
        <p class="eyebrow">Trade 管理事实</p>
        <h1>售后审核</h1>
        <p>
          这里只裁决整单售后的审核决定。退货、库存回补与退款继续由各自所有者域推进，
          页面不会把后续状态伪装成 Trade 的同步副作用。
        </p>
      </div>
      <span class="status-label">仅 ADMIN</span>
    </header>

    <PjStatusNotice tone="neutral" title="审核恢复边界">
      <p>
        审核接口没有独立命令 ID，但 Trade 会返回审核原因和状态。5xx 后原决定与原因
        会被冻结，必须先按售后号读取权威事实；只有仍为 APPLIED 时，才允许原载荷重试。
      </p>
    </PjStatusNotice>

    <PjSurface tone="soft" padding="large">
      <div class="after-sale-filter">
        <PjField label="售后状态" for-id="after-sale-status">
          <select
            id="after-sale-status"
            v-model="afterSale.status"
            class="pj-control"
            :disabled="afterSale.loading"
            @change="loadFacts"
          >
            <option
              v-for="option in statusOptions"
              :key="option.value || 'all'"
              :value="option.value"
            >
              {{ option.label }}
            </option>
          </select>
        </PjField>
        <div class="after-sale-filter__summary">
          <span>{{ afterSale.afterSales.length }} 条当前事实</span>
          <small v-if="afterSale.refreshedAt">
            最近读取 {{ formatDate(afterSale.refreshedAt) }}
          </small>
        </div>
        <PjButton
          type="button"
          variant="text"
          :loading="afterSale.loading"
          @click="loadFacts"
        >
          重新读取
        </PjButton>
      </div>
    </PjSurface>

    <PjStatusNotice
      v-if="afterSale.reviewPhase !== 'idle' && afterSale.reviewMessage"
      class="after-sale-command-notice"
      :tone="noticeTone(afterSale.reviewPhase)"
      :title="noticeTitle(afterSale.reviewPhase)"
      :assertive="afterSale.reviewPhase === 'rejected'"
    >
      <p>{{ afterSale.reviewMessage }}</p>
      <p v-if="afterSale.pendingReferenceNo">
        待确认售后：<code>{{ afterSale.pendingReferenceNo }}</code>；
        决定：{{ afterSale.pendingDecision }}。
      </p>
      <template #actions>
        <PjActionGroup>
          <PjButton
            v-if="afterSale.reviewPhase === 'unknown'"
            type="button"
            variant="secondary"
            :loading="afterSale.submitting"
            @click="afterSale.readPendingAuthority(accessContext)"
          >
            读取 Trade 权威事实
          </PjButton>
          <PjButton
            v-if="afterSale.canRetryPending"
            type="button"
            :loading="afterSale.submitting"
            @click="afterSale.retryPending(accessContext)"
          >
            使用原审核载荷重试
          </PjButton>
          <PjButton
            v-if="!afterSale.pendingReferenceNo"
            type="button"
            variant="text"
            @click="afterSale.resetReviewNotice"
          >
            关闭提示
          </PjButton>
        </PjActionGroup>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="afterSale.loadError"
      tone="danger"
      title="售后列表读取未完成"
      assertive
    >
      <p>{{ afterSale.loadError }}</p>
      <p v-if="afterSale.afterSales.length > 0">
        页面保留上一次已经显示的 Trade 售后事实，没有把读取失败伪装成空列表。
      </p>
    </PjStatusNotice>

    <div
      v-if="afterSale.loading && afterSale.afterSales.length === 0"
      class="after-sale-state"
      role="status"
    >
      正在读取 Trade 售后事实…
    </div>
    <div
      v-else-if="!afterSale.loading && afterSale.afterSales.length === 0"
      class="after-sale-state"
    >
      当前筛选下没有售后单。
    </div>
    <section
      v-else
      class="after-sale-list"
      aria-label="售后事实列表"
    >
      <PjSurface
        v-for="item in afterSale.afterSales"
        :key="item.afterSaleNo"
        as="article"
        tone="plain"
        padding="none"
        class="after-sale-record"
      >
        <header class="after-sale-record__header">
          <div>
            <p class="eyebrow">{{ item.afterSaleNo }}</p>
            <h2>订单 {{ item.orderNo }}</h2>
          </div>
          <span
            class="after-sale-status"
            :data-tone="presentation(item).tone"
          >
            {{ presentation(item).label }}
          </span>
        </header>

        <div class="after-sale-record__journey">
          <div>
            <span>当前处理方</span>
            <strong>{{ presentation(item).owner }}</strong>
          </div>
          <p>{{ presentation(item).next }}</p>
        </div>

        <dl class="after-sale-facts">
          <div>
            <dt>顾客 ID</dt>
            <dd><code>{{ item.userId }}</code></dd>
          </div>
          <div>
            <dt>退款金额</dt>
            <dd>{{ formatMoney(item.refundAmount) }}</dd>
          </div>
          <div>
            <dt>申请原因</dt>
            <dd>{{ item.reason }}</dd>
          </div>
          <div>
            <dt>审核原因</dt>
            <dd>{{ item.reviewReason || "尚未审核" }}</dd>
          </div>
          <div>
            <dt>退货单</dt>
            <dd><code>{{ item.returnReceiptNo || "尚未建立" }}</code></dd>
          </div>
          <div>
            <dt>退款单</dt>
            <dd><code>{{ item.refundNo || "尚未建立" }}</code></dd>
          </div>
          <div>
            <dt>申请时间</dt>
            <dd>{{ formatDate(item.createdAt) }}</dd>
          </div>
          <div>
            <dt>批准时间</dt>
            <dd>{{ formatDate(item.approvedAt) }}</dd>
          </div>
        </dl>

        <section
          class="after-sale-items"
          :aria-labelledby="fieldId('after-sale-items', item.afterSaleNo)"
        >
          <header>
            <div>
              <p class="eyebrow">不可变退款快照</p>
              <h3 :id="fieldId('after-sale-items', item.afterSaleNo)">
                {{ item.items.length }} 个订单行
              </h3>
            </div>
            <strong>{{ formatMoney(item.refundAmount) }}</strong>
          </header>
          <div
            v-for="line in item.items"
            :key="line.lineNo"
            class="after-sale-item"
          >
            <div>
              <strong>{{ line.productTitle }}</strong>
              <span>{{ line.skuName }} · SKU {{ line.skuId }}</span>
            </div>
            <dl>
              <div><dt>数量</dt><dd>{{ line.quantity }}</dd></div>
              <div><dt>行金额</dt><dd>{{ formatMoney(line.lineAmount) }}</dd></div>
              <div><dt>优惠分摊</dt><dd>{{ formatMoney(line.discountAmount) }}</dd></div>
              <div><dt>可退金额</dt><dd>{{ formatMoney(line.refundableAmount) }}</dd></div>
            </dl>
          </div>
        </section>

        <PjSurface
          v-if="item.status === 'APPLIED'"
          tone="soft"
          padding="medium"
          class="after-sale-review"
        >
          <form @submit.prevent="afterSale.review(item.afterSaleNo, accessContext)">
            <header>
              <div>
                <p class="eyebrow">Trade 审核命令</p>
                <h3>记录审核决定</h3>
              </div>
              <span>只有明确响应或匹配的权威事实才能显示成功</span>
            </header>
            <div class="after-sale-review__fields">
              <PjField
                label="审核决定"
                :for-id="fieldId('after-sale-decision', item.afterSaleNo)"
                required
              >
                <select
                  :id="fieldId('after-sale-decision', item.afterSaleNo)"
                  v-model="afterSale.reviewForm(item.afterSaleNo).approved"
                  class="pj-control"
                  :disabled="afterSale.commandBlocked"
                >
                  <option :value="true">审核通过</option>
                  <option :value="false">审核拒绝</option>
                </select>
              </PjField>
              <PjField
                label="审核原因"
                :for-id="fieldId('after-sale-reason', item.afterSaleNo)"
                hint="最多 500 字；结果未知时原文会被冻结。"
                required
              >
                <textarea
                  :id="fieldId('after-sale-reason', item.afterSaleNo)"
                  v-model="afterSale.reviewForm(item.afterSaleNo).reason"
                  class="pj-control"
                  rows="4"
                  maxlength="500"
                  required
                  :readonly="afterSale.commandBlocked"
                ></textarea>
              </PjField>
            </div>
            <PjButton
              type="submit"
              :loading="
                afterSale.submitting
                  && afterSale.pendingReferenceNo === item.afterSaleNo
              "
              :disabled="afterSale.commandBlocked"
            >
              提交审核决定
            </PjButton>
          </form>
        </PjSurface>
      </PjSurface>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.after-sale-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.after-sale-hero,
.after-sale-filter,
.after-sale-record__header,
.after-sale-items > header,
.after-sale-review header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.after-sale-hero h1,
.after-sale-record h2,
.after-sale-record h3 {
  margin: 0;
}

.after-sale-hero h1 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.after-sale-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-color-muted);
  line-height: var(--pj-line-height-relaxed);
}

.after-sale-filter {
  align-items: center;
}

.after-sale-filter .pj-field {
  width: min(100%, 22rem);
}

.after-sale-filter__summary {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
  margin-left: auto;
  color: var(--pj-color-muted);
  text-align: right;
}

.after-sale-filter__summary span {
  color: var(--pj-color-text);
}

.after-sale-state {
  min-height: 14rem;
  display: grid;
  place-items: center;
  color: var(--pj-color-muted);
  border-block: 1px solid var(--pj-color-line);
}

.after-sale-list {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
}

.after-sale-record {
  min-width: 0;
  overflow: hidden;
  border-block: 1px solid var(--pj-color-line);
}

.after-sale-record__header,
.after-sale-record__journey,
.after-sale-facts,
.after-sale-items,
.after-sale-review {
  padding-inline: var(--pj-space-6);
}

.after-sale-record__header {
  padding-block: var(--pj-space-6);
}

.after-sale-record__header h2 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xl);
  font-weight: 540;
}

.after-sale-status {
  flex: 0 0 auto;
  padding: var(--pj-space-1) var(--pj-space-3);
  border: 1px solid var(--pj-color-line);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.after-sale-status[data-tone="warning"] {
  border-color: var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
}

.after-sale-status[data-tone="processing"] {
  border-color: var(--pj-status-processing-line);
  color: var(--pj-status-processing-text);
}

.after-sale-status[data-tone="attention"] {
  border-color: var(--pj-status-attention-line);
  color: var(--pj-status-attention-text);
}

.after-sale-status[data-tone="success"] {
  border-color: var(--pj-status-success-line);
  color: var(--pj-status-success-text);
}

.after-sale-status[data-tone="danger"] {
  border-color: var(--pj-status-danger-line);
  color: var(--pj-status-danger-text);
}

.after-sale-record__journey {
  display: grid;
  grid-template-columns: minmax(12rem, 0.35fr) minmax(0, 1fr);
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-5);
  border-block: 1px solid var(--pj-color-line);
  background: var(--pj-color-surface-soft);
}

.after-sale-record__journey div {
  display: grid;
  gap: var(--pj-space-1);
}

.after-sale-record__journey span {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.after-sale-record__journey p {
  margin: 0;
  color: var(--pj-color-muted);
  line-height: var(--pj-line-height-relaxed);
}

.after-sale-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4) var(--pj-space-5);
  margin: 0;
  padding-block: var(--pj-space-6);
}

.after-sale-facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-facts dt,
.after-sale-item dt {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.after-sale-facts dd,
.after-sale-item dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.after-sale-items {
  padding-block: var(--pj-space-6);
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-items > header {
  margin-bottom: var(--pj-space-4);
}

.after-sale-item {
  display: grid;
  grid-template-columns: minmax(12rem, 1fr) minmax(22rem, 1.4fr);
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-item > div {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--pj-space-1);
}

.after-sale-item span {
  color: var(--pj-color-muted);
  overflow-wrap: anywhere;
}

.after-sale-item dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-3);
  margin: 0;
}

.after-sale-review {
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-review form {
  display: grid;
  gap: var(--pj-space-5);
}

.after-sale-review header {
  align-items: flex-start;
}

.after-sale-review header > span {
  max-width: 28rem;
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
  text-align: right;
}

.after-sale-review__fields {
  display: grid;
  grid-template-columns: minmax(12rem, 0.35fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
}

.after-sale-review textarea {
  resize: vertical;
  line-height: var(--pj-line-height-relaxed);
}

@media (max-width: 64rem) {
  .after-sale-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .after-sale-item {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 48rem) {
  .after-sale-hero,
  .after-sale-filter,
  .after-sale-record__header,
  .after-sale-items > header,
  .after-sale-review header {
    align-items: flex-start;
    flex-direction: column;
  }

  .after-sale-filter .pj-field {
    width: 100%;
  }

  .after-sale-filter__summary {
    margin-left: 0;
    text-align: left;
  }

  .after-sale-record__journey,
  .after-sale-review__fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .after-sale-review header > span {
    text-align: left;
  }
}

@media (max-width: 32rem) {
  .after-sale-record__header,
  .after-sale-record__journey,
  .after-sale-facts,
  .after-sale-items,
  .after-sale-review {
    padding-inline: var(--pj-space-4);
  }

  .after-sale-facts,
  .after-sale-item dl {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
