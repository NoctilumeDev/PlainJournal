<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import { formatMoney, type AfterSale } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  useAdminAfterSaleStore,
  type AfterSaleReviewPhase,
} from "../entities/admin-after-sale";
import { SplitWorkbench } from "../shared/ui";
import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
const afterSale = useAdminAfterSaleStore();
const selectedReferenceNo = ref<string | null>(null);
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
  { value: "", label: "全部售后" },
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

const selectedItem = computed(() => {
  if (afterSale.afterSales.length === 0) {
    return null;
  }
  return afterSale.afterSales.find((item) =>
    item.afterSaleNo === selectedReferenceNo.value)
    ?? afterSale.afterSales[0];
});

const currentStatusLabel = computed(() =>
  statusOptions.find((option) => option.value === afterSale.status)?.label
  ?? "全部售后");

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
    unknown: "当前结论尚不能确认",
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

function visibleCount(value: string) {
  if (!afterSale.status || value === afterSale.status || value === "") {
    return value
      ? afterSale.afterSales.filter((item) => item.status === value).length
      : afterSale.afterSales.length;
  }
  return null;
}

async function loadFacts() {
  await afterSale.loadFacts(accessContext.value);
}

async function selectStatus(value: string) {
  if (afterSale.status === value && afterSale.afterSales.length > 0) {
    return;
  }
  afterSale.status = value;
  selectedReferenceNo.value = null;
  await loadFacts();
}

function selectItem(item: AfterSale) {
  selectedReferenceNo.value = item.afterSaleNo;
}

async function submitReview(item: AfterSale, approved: boolean) {
  afterSale.reviewForm(item.afterSaleNo).approved = approved;
  await afterSale.review(item.afterSaleNo, accessContext.value);
}

watch(accessContext, (context) => {
  afterSale.synchronizeAccess(context);
});

watch(
  () => afterSale.afterSales.map((item) => item.afterSaleNo),
  (references) => {
    if (
      references.length > 0
      && !references.includes(selectedReferenceNo.value ?? "")
    ) {
      selectedReferenceNo.value = references[0] ?? null;
    }
  },
  { immediate: true },
);

onMounted(() => {
  afterSale.synchronizeAccess(accessContext.value);
  void loadFacts();
});
</script>

<template>
  <SplitWorkbench label="售后审核工作台" class="after-sale-workbench">
    <template #rail>
      <div class="after-sale-rail">
        <header class="after-sale-rail__header">
          <p class="eyebrow">任务队列</p>
          <h1>售后审核</h1>
          <p>按状态收窄任务，再进入单个售后事实。</p>
        </header>

        <nav class="after-sale-status-nav" aria-label="售后状态筛选">
          <button
            v-for="option in statusOptions"
            :key="option.value || 'all'"
            type="button"
            :aria-current="afterSale.status === option.value ? 'page' : undefined"
            :disabled="afterSale.loading"
            @click="selectStatus(option.value)"
          >
            <span>{{ option.label }}</span>
            <small v-if="visibleCount(option.value) !== null">
              {{ visibleCount(option.value) }}
            </small>
          </button>
        </nav>

        <section class="after-sale-rail__boundary" aria-labelledby="after-sale-boundary-title">
          <p class="eyebrow">审核边界</p>
          <h2 id="after-sale-boundary-title">Trade 只记录审核事实</h2>
          <p>退货、库存回补与退款继续由各自所有者推进。</p>
        </section>

        <PjButton
          type="button"
          variant="text"
          :loading="afterSale.loading"
          @click="loadFacts"
        >
          重新读取权威事实
        </PjButton>
      </div>
    </template>

    <template #queue>
      <div class="after-sale-queue">
        <header class="after-sale-panel-header">
          <div>
            <p class="eyebrow">{{ currentStatusLabel }}</p>
            <h2>{{ afterSale.afterSales.length }} 条当前事实</h2>
          </div>
          <small v-if="afterSale.refreshedAt">
            读取于 {{ formatDate(afterSale.refreshedAt) }}
          </small>
        </header>

        <PjStatusNotice
          v-if="afterSale.loadError"
          tone="danger"
          title="售后列表读取未完成"
          assertive
        >
          <p>{{ afterSale.loadError }}</p>
          <p v-if="afterSale.afterSales.length > 0">
            保留上一次已显示的 Trade 事实。
          </p>
        </PjStatusNotice>

        <div
          v-if="afterSale.loading && afterSale.afterSales.length === 0"
          class="after-sale-empty"
          role="status"
        >
          <strong>正在读取售后事实</strong>
          <span>列表会保留当前筛选条件。</span>
        </div>
        <div
          v-else-if="!afterSale.loading && afterSale.afterSales.length === 0"
          class="after-sale-empty"
        >
          <strong>当前筛选下没有售后单</strong>
          <span>可切换状态或重新读取权威事实。</span>
        </div>
        <ol v-else class="after-sale-queue__list">
          <li v-for="item in afterSale.afterSales" :key="item.afterSaleNo">
            <button
              type="button"
              :class="{ 'is-selected': selectedItem?.afterSaleNo === item.afterSaleNo }"
              :aria-pressed="selectedItem?.afterSaleNo === item.afterSaleNo"
              @click="selectItem(item)"
            >
              <span class="after-sale-queue__identity">
                <code>{{ item.afterSaleNo }}</code>
                <strong>订单 {{ item.orderNo }}</strong>
              </span>
              <span class="after-sale-queue__amount">{{ formatMoney(item.refundAmount) }}</span>
              <span class="after-sale-queue__reason">{{ item.reason }}</span>
              <span class="after-sale-queue__meta">
                <span class="after-sale-status" :data-tone="presentation(item).tone">
                  {{ presentation(item).label }}
                </span>
                <time :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
              </span>
            </button>
          </li>
        </ol>
      </div>
    </template>

    <template #detail>
      <article v-if="selectedItem" class="after-sale-detail">
        <header class="after-sale-detail__header">
          <div>
            <p class="eyebrow">申请单号 {{ selectedItem.afterSaleNo }}</p>
            <h2>订单 {{ selectedItem.orderNo }}</h2>
          </div>
          <span class="after-sale-status" :data-tone="presentation(selectedItem).tone">
            {{ presentation(selectedItem).label }}
          </span>
        </header>

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

        <section class="after-sale-detail__section" aria-labelledby="after-sale-order-facts">
          <header>
            <h3 id="after-sale-order-facts">订单与退款事实</h3>
            <p>{{ presentation(selectedItem).owner }}</p>
          </header>
          <dl class="after-sale-facts">
            <div><dt>顾客 ID</dt><dd><code>{{ selectedItem.userId }}</code></dd></div>
            <div><dt>退款金额</dt><dd>{{ formatMoney(selectedItem.refundAmount) }}</dd></div>
            <div><dt>申请原因</dt><dd>{{ selectedItem.reason }}</dd></div>
            <div><dt>审核原因</dt><dd>{{ selectedItem.reviewReason || "尚未审核" }}</dd></div>
            <div><dt>退货单</dt><dd><code>{{ selectedItem.returnReceiptNo || "尚未建立" }}</code></dd></div>
            <div><dt>退款单</dt><dd><code>{{ selectedItem.refundNo || "尚未建立" }}</code></dd></div>
            <div><dt>申请时间</dt><dd>{{ formatDate(selectedItem.createdAt) }}</dd></div>
            <div><dt>批准时间</dt><dd>{{ formatDate(selectedItem.approvedAt) }}</dd></div>
          </dl>
        </section>

        <section class="after-sale-detail__section after-sale-next-step" aria-labelledby="after-sale-next-step">
          <header>
            <h3 id="after-sale-next-step">当前处理边界</h3>
          </header>
          <div class="after-sale-next-step__grid">
            <div>
              <span>已确认</span>
              <strong>{{ presentation(selectedItem).label }}</strong>
            </div>
            <div>
              <span>当前所有者</span>
              <strong>{{ presentation(selectedItem).owner }}</strong>
            </div>
            <div>
              <span>下一步</span>
              <strong>{{ presentation(selectedItem).next }}</strong>
            </div>
          </div>
        </section>

        <section class="after-sale-detail__section" :aria-labelledby="fieldId('after-sale-items', selectedItem.afterSaleNo)">
          <header>
            <div>
              <h3 :id="fieldId('after-sale-items', selectedItem.afterSaleNo)">
                不可变退款快照
              </h3>
              <p>{{ selectedItem.items.length }} 个订单行</p>
            </div>
            <strong>{{ formatMoney(selectedItem.refundAmount) }}</strong>
          </header>
          <div class="after-sale-items">
            <article v-for="line in selectedItem.items" :key="line.lineNo">
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
            </article>
          </div>
        </section>

        <form
          v-if="selectedItem.status === 'APPLIED'"
          class="after-sale-review"
          @submit.prevent
        >
          <div class="after-sale-review__copy">
            <p class="eyebrow">Trade 审核命令</p>
            <h3>记录审核决定</h3>
            <p>只有明确响应或匹配的权威事实才能显示成功。</p>
          </div>
          <PjField
            label="审核原因"
            :for-id="fieldId('after-sale-reason', selectedItem.afterSaleNo)"
            hint="最多 500 字；结果未知时原文会被冻结。"
            required
          >
            <textarea
              :id="fieldId('after-sale-reason', selectedItem.afterSaleNo)"
              v-model="afterSale.reviewForm(selectedItem.afterSaleNo).reason"
              class="pj-control"
              rows="3"
              maxlength="500"
              required
              :readonly="afterSale.commandBlocked"
            ></textarea>
          </PjField>
          <PjActionGroup align="end">
            <PjButton
              type="button"
              :loading="afterSale.submitting"
              :disabled="afterSale.commandBlocked"
              @click="submitReview(selectedItem, true)"
            >
              批准退款
            </PjButton>
            <PjButton
              type="button"
              variant="destructive"
              :disabled="afterSale.commandBlocked"
              @click="submitReview(selectedItem, false)"
            >
              拒绝申请
            </PjButton>
          </PjActionGroup>
        </form>
      </article>
      <div v-else class="after-sale-detail after-sale-empty">
        <strong>尚未选择售后单</strong>
        <span>先从任务队列读取并选择一条权威事实。</span>
      </div>
    </template>
  </SplitWorkbench>
</template>

<style scoped>
.after-sale-workbench {
  color: var(--pj-color-text);
}

.after-sale-rail,
.after-sale-queue,
.after-sale-detail {
  min-width: 0;
  height: calc(100vh - var(--admin-shell-header-height));
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.after-sale-rail,
.after-sale-queue {
  padding: var(--pj-space-5);
}

.after-sale-rail__header,
.after-sale-panel-header,
.after-sale-detail__header,
.after-sale-detail__section > header,
.after-sale-review__copy {
  min-width: 0;
}

.after-sale-rail__header h1,
.after-sale-panel-header h2,
.after-sale-detail__header h2,
.after-sale-detail__section h3,
.after-sale-review h3 {
  margin: 0;
  font-weight: 560;
  letter-spacing: 0.012em;
}

.after-sale-rail__header h1 {
  font-size: clamp(1.5rem, 2.2vw, 2rem);
  white-space: nowrap;
}

.after-sale-rail__header > p:last-child,
.after-sale-panel-header small,
.after-sale-detail__section > header p,
.after-sale-review__copy p:last-child,
.after-sale-empty span {
  color: var(--pj-color-muted);
}

.after-sale-status-nav {
  display: grid;
  gap: var(--pj-space-1);
  margin-top: var(--pj-space-6);
}

.after-sale-status-nav button {
  min-height: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-3);
  padding: var(--pj-space-2) var(--pj-space-3);
  border: 0;
  background: transparent;
  color: var(--pj-color-muted);
  cursor: pointer;
  text-align: left;
}

.after-sale-status-nav button:hover,
.after-sale-status-nav button[aria-current="page"] {
  background: var(--pj-color-surface-soft);
  color: var(--pj-color-text);
}

.after-sale-status-nav button[aria-current="page"] {
  font-weight: 650;
}

.after-sale-status-nav small {
  flex: 0 0 auto;
}

.after-sale-rail__boundary {
  margin-block: var(--pj-space-7) var(--pj-space-5);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-rail__boundary h2 {
  margin: 0;
  font-size: var(--pj-font-size-md);
}

.after-sale-rail__boundary p:last-child {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.after-sale-panel-header,
.after-sale-detail__header,
.after-sale-detail__section > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.after-sale-panel-header {
  padding-bottom: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-color-line);
}

.after-sale-panel-header h2 {
  font-size: var(--pj-font-size-lg);
}

.after-sale-queue > .pj-status-notice {
  margin-block: var(--pj-space-4);
}

.after-sale-queue__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.after-sale-queue__list li {
  border-bottom: 1px solid var(--pj-color-line);
}

.after-sale-queue__list button {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--pj-space-2) var(--pj-space-4);
  padding: var(--pj-space-4) var(--pj-space-3);
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.after-sale-queue__list button:hover,
.after-sale-queue__list button.is-selected {
  background: color-mix(in srgb, var(--pj-color-accent-soft) 28%, var(--pj-color-surface));
}

.after-sale-queue__list button.is-selected {
  box-shadow: inset 0.2rem 0 0 var(--pj-color-accent-strong);
}

.after-sale-queue__identity,
.after-sale-queue__meta {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--pj-space-2);
}

.after-sale-queue__identity {
  flex-wrap: wrap;
}

.after-sale-queue__identity code,
.after-sale-queue__identity strong,
.after-sale-queue__reason {
  overflow-wrap: anywhere;
}

.after-sale-queue__amount {
  font-weight: 650;
}

.after-sale-queue__reason,
.after-sale-queue__meta {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.after-sale-queue__meta {
  justify-content: space-between;
}

.after-sale-queue__meta time {
  text-align: right;
}

.after-sale-status {
  width: fit-content;
  flex: 0 0 auto;
  padding: var(--pj-space-1) var(--pj-space-2);
  border: 1px solid var(--pj-color-line);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
  line-height: 1.2;
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

.after-sale-detail {
  padding: var(--pj-space-5) var(--pj-space-6) var(--pj-space-7);
}

.after-sale-detail__header {
  padding-bottom: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-color-line);
}

.after-sale-detail__header h2 {
  overflow-wrap: anywhere;
  font-size: clamp(1.5rem, 2.4vw, 2.25rem);
}

.after-sale-command-notice {
  margin-top: var(--pj-space-5);
}

.after-sale-detail__section {
  padding-block: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-color-line);
}

.after-sale-detail__section h3,
.after-sale-review h3 {
  font-size: var(--pj-font-size-md);
}

.after-sale-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4) var(--pj-space-7);
  margin: var(--pj-space-5) 0 0;
}

.after-sale-facts div,
.after-sale-items article {
  min-width: 0;
}

.after-sale-facts dt,
.after-sale-items dt,
.after-sale-next-step__grid span {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.after-sale-facts dd,
.after-sale-items dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.after-sale-next-step__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: var(--pj-space-5);
  border: 1px solid var(--pj-color-line-strong);
}

.after-sale-next-step__grid > div {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--pj-space-2);
  padding: var(--pj-space-4);
}

.after-sale-next-step__grid > div + div {
  border-left: 1px solid var(--pj-color-line);
}

.after-sale-next-step__grid strong {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-sm);
  font-weight: 560;
}

.after-sale-items {
  margin-top: var(--pj-space-5);
}

.after-sale-items article {
  display: grid;
  grid-template-columns: minmax(12rem, 1fr) minmax(20rem, 1.4fr);
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-color-line);
}

.after-sale-items article > div {
  display: grid;
  gap: var(--pj-space-1);
}

.after-sale-items article > div span {
  color: var(--pj-color-muted);
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-sm);
}

.after-sale-items dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-3);
  margin: 0;
}

.after-sale-review {
  position: sticky;
  bottom: calc(-1 * var(--pj-space-7));
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(14rem, 0.8fr) minmax(18rem, 1.2fr);
  gap: var(--pj-space-5);
  margin-inline: calc(-1 * var(--pj-space-6));
  padding: var(--pj-space-5) var(--pj-space-6);
  border-top: 1px solid var(--pj-color-line-strong);
  background: color-mix(in srgb, var(--pj-color-surface) 97%, transparent);
}

.after-sale-review > .pj-action-group {
  grid-column: 2;
}

.after-sale-empty {
  min-height: 14rem;
  display: grid;
  place-content: center;
  gap: var(--pj-space-2);
  text-align: center;
}

@media (max-width: 72rem) {
  .after-sale-detail {
    height: auto;
    overflow: visible;
  }

  .after-sale-review {
    bottom: 0;
  }
}

@media (max-width: 48rem) {
  .after-sale-rail,
  .after-sale-queue,
  .after-sale-detail {
    height: auto;
    overflow: visible;
  }

  .after-sale-rail,
  .after-sale-queue,
  .after-sale-detail {
    padding: var(--pj-space-5) var(--pj-layout-gutter);
  }

  .after-sale-facts,
  .after-sale-next-step__grid,
  .after-sale-review,
  .after-sale-items article,
  .after-sale-items dl {
    grid-template-columns: minmax(0, 1fr);
  }

  .after-sale-next-step__grid > div + div {
    border-top: 1px solid var(--pj-color-line);
    border-left: 0;
  }

  .after-sale-items article {
    gap: var(--pj-space-4);
  }

  .after-sale-review {
    position: static;
    margin: 0;
    padding-inline: 0;
    background: transparent;
  }

  .after-sale-review > .pj-action-group {
    grid-column: auto;
  }
}
</style>
