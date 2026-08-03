<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";

import { formatMoney } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  afterSaleStatusPresentation,
  useAfterSalesStore,
  type AfterSaleAccessContext,
} from "../../../entities/after-sale";
import {
  refundStatusPresentation,
  useRefundsStore,
} from "../../../entities/refund";
import {
  returnReceiptStatusPresentation,
  useReturnReceiptsStore,
} from "../../../entities/return-receipt";
import AfterSaleProgress from "./AfterSaleProgress.vue";

const props = defineProps<{
  afterSaleNo: string;
  access: AfterSaleAccessContext;
}>();

const afterSales = useAfterSalesStore();
const returnReceipts = useReturnReceiptsStore();
const refunds = useRefundsStore();
const afterSale = computed(() => afterSales.find(props.afterSaleNo));
const returnReceipt = computed(() =>
  afterSale.value ? returnReceipts.forAfterSale(afterSale.value.afterSaleNo) : null);
const refund = computed(() =>
  afterSale.value ? refunds.forAfterSale(afterSale.value.afterSaleNo) : null);
const statusCopy = computed(() =>
  afterSale.value ? afterSaleStatusPresentation(afterSale.value) : null);
const statusTone = computed(() => statusCopy.value?.tone ?? "neutral");
const statusTitle = computed(() => statusCopy.value?.title ?? "售后状态待确认");
const returnCopy = computed(() =>
  returnReceipt.value ? returnReceiptStatusPresentation(returnReceipt.value) : null);
const returnTone = computed(() => returnCopy.value?.tone ?? "neutral");
const returnTitle = computed(() => returnCopy.value?.label ?? "退货状态待确认");
const refundCopy = computed(() =>
  refund.value ? refundStatusPresentation(refund.value) : null);
const refundTone = computed(() => refundCopy.value?.tone ?? "neutral");
const refundTitle = computed(() => refundCopy.value?.label ?? "退款状态待确认");
const shipment = reactive({ carrier: "", trackingNo: "" });
const feedback = ref<{
  tone: "success" | "unknown";
  title: string;
  message: string;
} | null>(null);
const confirmCancel = ref(false);

function formatTimestamp(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN") : "—";
}

async function refresh() {
  feedback.value = null;
  confirmCancel.value = false;
  const value = await afterSales.loadOne(props.access, props.afterSaleNo);
  if (!value) {
    return;
  }
  await Promise.all([
    value.returnReceiptNo
      ? returnReceipts.loadOne(props.access, value.returnReceiptNo)
      : returnReceipts.load(props.access),
    refunds.loadByAfterSale(props.access, value.afterSaleNo),
  ]);
}

async function submitShipment() {
  feedback.value = null;
  const receipt = returnReceipt.value;
  if (!receipt) {
    return;
  }
  const value = await returnReceipts.submitShipment(
    props.access,
    receipt.returnReceiptNo,
    shipment.carrier,
    shipment.trackingNo,
  );
  if (value?.status === "RETURNING") {
    feedback.value = {
      tone: "success",
      title: "寄回事实已确认",
      message: "Fulfillment 已保存本次运单，正在等待仓库收货。",
    };
    await afterSales.loadOne(props.access, props.afterSaleNo);
  } else if (returnReceipts.submissionUnknown) {
    feedback.value = {
      tone: "unknown",
      title: "寄回结果待确认",
      message: "页面没有提前显示提交成功。请先查询退货事实，不要更换运单号重复提交。",
    };
  }
}

async function cancelApplication() {
  confirmCancel.value = false;
  feedback.value = null;
  const value = await afterSales.cancel(props.access, props.afterSaleNo);
  if (value?.status === "CANCELED") {
    feedback.value = {
      tone: "success",
      title: "售后申请已取消",
      message: "Trade 已确认取消完成，不会再建立退货和退款流程。",
    };
  } else if (afterSales.cancellationUnknown) {
    feedback.value = {
      tone: "unknown",
      title: "取消结果待确认",
      message: "请查询 Trade 售后事实后，再决定是否重试取消。",
    };
  }
}

watch(
  () => [
    props.afterSaleNo,
    props.access.authenticated,
    props.access.ownerId,
    props.access.accessToken,
  ],
  refresh,
  { immediate: true },
);
</script>

<template>
  <PjStatusNotice
    v-if="afterSales.loadingNo === afterSaleNo && !afterSale"
    tone="processing"
    title="正在读取售后进度"
  >
    <p>页面正在查询售后、退货与退款的最新事实。</p>
  </PjStatusNotice>

  <PjStatusNotice
    v-else-if="afterSales.error && !afterSale"
    tone="danger"
    title="售后详情读取未完成"
    assertive
  >
    <p>{{ afterSales.error }}</p>
    <template #actions>
      <PjButton variant="secondary" @click="refresh">重新查询</PjButton>
    </template>
  </PjStatusNotice>

  <div v-else-if="afterSale" class="after-sale-workspace">
    <main class="after-sale-main">
      <PjStatusNotice
        class="after-sale-overview"
        :tone="statusTone"
        :title="statusTitle"
      >
        <p>{{ statusCopy?.detail }}</p>
        <p class="after-sale-overview__meta">
          {{ statusCopy?.label }} · 更新于 {{ formatTimestamp(afterSale.updatedAt) }}
        </p>
        <template #actions>
          <PjActionGroup class="after-sale-overview__actions">
            <PjButton
              variant="secondary"
              :loading="afterSales.loadingNo === afterSaleNo"
              @click="refresh"
            >
              刷新售后进度
            </PjButton>
            <PjButton
              v-if="afterSale.status === 'APPLIED'"
              variant="text"
              class="text-action--danger"
              @click="confirmCancel = true"
            >
              取消售后申请
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>

      <AfterSaleProgress :after-sale="afterSale" />

      <PjStatusNotice
        v-if="confirmCancel && afterSale.status === 'APPLIED'"
        class="after-sale-cancel-confirmation"
        tone="danger"
        title="确认取消售后申请"
        assertive
      >
        <p>取消后不会建立退货和退款流程。确定取消本次申请吗？</p>
        <template #actions>
          <PjActionGroup>
            <PjButton
              variant="destructive"
              :loading="afterSales.cancelingNo === afterSaleNo"
              @click="cancelApplication"
            >
              确认取消申请
            </PjButton>
            <PjButton
              variant="text"
              :disabled="afterSales.cancelingNo === afterSaleNo"
              @click="confirmCancel = false"
            >
              保留申请
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>

      <PjStatusNotice
        v-if="feedback"
        class="after-sale-feedback"
        :tone="feedback.tone"
        :title="feedback.title"
      >
        <p>{{ feedback.message }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="afterSales.cancellationUnknown && feedback?.tone !== 'unknown'"
        tone="unknown"
        title="取消结果待确认"
      >
        <p>{{ afterSales.cancellationError }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="afterSales.cancellationError && !afterSales.cancellationUnknown"
        tone="danger"
        title="取消售后未完成"
        assertive
      >
        <p>{{ afterSales.cancellationError }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="afterSales.error"
        tone="danger"
        title="售后事实刷新未完成"
        assertive
      >
        <p>{{ afterSales.error }}</p>
      </PjStatusNotice>

      <section
        class="after-sale-journey-section after-sale-return-section"
        aria-labelledby="return-title"
      >
        <header class="after-sale-section-header">
          <p>寄回</p>
          <h2 id="return-title">寄回与仓库</h2>
          <span>运单提交、仓库收货和验收沿同一条退货事实更新。</span>
        </header>

        <template v-if="returnReceipt">
          <PjStatusNotice
            class="return-receipt-state"
            :tone="returnTone"
            :title="returnTitle"
          >
            <p>{{ returnCopy?.detail }}</p>
            <p class="after-sale-state-code">{{ returnReceipt.status }}</p>
          </PjStatusNotice>

          <dl class="after-sale-facts">
            <div>
              <dt>退货单号</dt>
              <dd>{{ returnReceipt.returnReceiptNo }}</dd>
            </div>
            <div>
              <dt>仓库</dt>
              <dd>{{ returnReceipt.warehouseId }}</dd>
            </div>
            <div>
              <dt>运单</dt>
              <dd>
                {{
                  returnReceipt.trackingNo
                    ? `${returnReceipt.carrier} / ${returnReceipt.trackingNo}`
                    : "尚未提交"
                }}
              </dd>
            </div>
            <div>
              <dt>仓库收货</dt>
              <dd>{{ formatTimestamp(returnReceipt.receivedAt) }}</dd>
            </div>
            <div>
              <dt>仓库验收</dt>
              <dd>{{ formatTimestamp(returnReceipt.inspectedAt) }}</dd>
            </div>
            <div>
              <dt>验收说明</dt>
              <dd>{{ returnReceipt.inspectionRemark || "尚未形成" }}</dd>
            </div>
          </dl>

          <PjSurface
            v-if="returnReceipt.status === 'WAIT_SHIPMENT'"
            as="form"
            tone="soft"
            padding="medium"
            class="after-sale-shipment"
            @submit.prevent="submitShipment"
          >
            <div class="after-sale-shipment__intro">
              <strong>填写真实寄回信息</strong>
              <p>运单会成为不可覆盖的履约事实，提交前请再次核对。</p>
            </div>
            <PjField
              label="承运商代码"
              for-id="return-carrier"
              hint="只使用承运商提供的字母、数字、下划线或连字符代码。"
              required
            >
              <input
                id="return-carrier"
                v-model.trim="shipment.carrier"
                class="pj-control"
                required
                maxlength="40"
                pattern="[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*"
                autocomplete="off"
                placeholder="SF"
              />
            </PjField>
            <PjField
              label="运单号"
              for-id="return-tracking-no"
              hint="结果未知时不要更换运单号重复提交。"
              required
            >
              <input
                id="return-tracking-no"
                v-model.trim="shipment.trackingNo"
                class="pj-control"
                required
                maxlength="100"
                pattern="[A-Za-z0-9._:]+(?:-[A-Za-z0-9._:]+)*"
                autocomplete="off"
                placeholder="SF1234567890"
              />
            </PjField>
            <PjButton
              type="submit"
              :loading="returnReceipts.submittingNo === returnReceipt.returnReceiptNo"
            >
              提交寄回信息
            </PjButton>
          </PjSurface>
        </template>

        <PjStatusNotice
          v-else
          tone="processing"
          title="退货单尚未建立"
        >
          <p>审核通过后，Fulfillment 才会通过事件建立退货单。</p>
        </PjStatusNotice>

        <PjStatusNotice
          v-if="returnReceipts.submissionUnknown && feedback?.tone !== 'unknown'"
          tone="unknown"
          title="寄回结果待确认"
        >
          <p>{{ returnReceipts.submissionError }}</p>
        </PjStatusNotice>
        <PjStatusNotice
          v-if="returnReceipts.submissionError && !returnReceipts.submissionUnknown"
          tone="danger"
          title="寄回信息提交未完成"
          assertive
        >
          <p>{{ returnReceipts.submissionError }}</p>
        </PjStatusNotice>
        <PjStatusNotice
          v-if="returnReceipts.error"
          tone="danger"
          title="退货事实读取未完成"
          assertive
        >
          <p>{{ returnReceipts.error }}</p>
        </PjStatusNotice>
      </section>

      <section
        class="after-sale-journey-section after-sale-refund-section"
        aria-labelledby="refund-title"
      >
        <header class="after-sale-section-header">
          <p>退款</p>
          <h2 id="refund-title">渠道退款</h2>
          <span>仓库验收不等于资金到账，最终结果只以 Payment 退款事实为准。</span>
        </header>

        <template v-if="refund">
          <PjStatusNotice
            class="refund-state"
            :tone="refundTone"
            :title="refundTitle"
          >
            <p>{{ refundCopy?.detail }}</p>
            <p v-if="refundCopy?.tone === 'processing'">
              退款仍在处理中，不会因为仓库已验收就提前显示到账。
            </p>
            <p v-if="refundCopy?.tone === 'attention'">
              顾客无需重复操作；平台会在授权、幂等与审计边界内核对并恢复。
            </p>
          </PjStatusNotice>

          <dl class="after-sale-facts">
            <div>
              <dt>退款单号</dt>
              <dd>{{ refund.refundNo }}</dd>
            </div>
            <div>
              <dt>退款渠道</dt>
              <dd>{{ refund.channel }}</dd>
            </div>
            <div>
              <dt>渠道请求</dt>
              <dd>{{ refund.requestStatus }} / {{ refund.requestAttempts }} 次</dd>
            </div>
            <div>
              <dt>退款金额</dt>
              <dd>{{ formatMoney(refund.amount) }}</dd>
            </div>
            <div>
              <dt>渠道退款号</dt>
              <dd>{{ refund.channelRefundNo || "尚未返回" }}</dd>
            </div>
            <div>
              <dt>成功时间</dt>
              <dd>{{ formatTimestamp(refund.refundedAt) }}</dd>
            </div>
          </dl>
        </template>

        <PjStatusNotice
          v-else
          tone="processing"
          title="退款事实尚未建立"
        >
          <p>仓库验收、库存回补和退款请求会通过消息逐步推进。</p>
        </PjStatusNotice>

        <PjStatusNotice
          v-if="refunds.error"
          tone="danger"
          title="退款事实读取未完成"
          assertive
        >
          <p>{{ refunds.error }}</p>
        </PjStatusNotice>
      </section>

      <section
        class="after-sale-journey-section after-sale-items"
        aria-labelledby="after-sale-items-title"
      >
        <header class="after-sale-section-header">
          <p>商品</p>
          <h2 id="after-sale-items-title">退款商品快照</h2>
          <span>退款金额继续使用下单时保存的商品行与优惠分摊事实。</span>
        </header>
        <article
          v-for="item in afterSale.items"
          :key="item.lineNo"
          class="after-sale-item"
        >
          <div>
            <strong>{{ item.productTitle }}</strong>
            <p>{{ item.skuName }} · {{ item.quantity }} 件</p>
          </div>
          <div class="after-sale-item__amount">
            <strong>{{ formatMoney(item.refundableAmount) }}</strong>
            <small>原始 {{ formatMoney(item.lineAmount) }}</small>
          </div>
        </article>
      </section>
    </main>

    <PjSurface
      as="aside"
      tone="soft"
      padding="large"
      class="after-sale-boundary"
    >
      <p class="after-sale-boundary__context">当前售后事实</p>
      <h2>独立裁决，连续呈现</h2>
      <p>
        售后、退货与退款由各自所有者裁决。任一中间状态或治理状态都不会被页面改写为成功。
      </p>
      <dl>
        <div>
          <dt>售后</dt>
          <dd>{{ afterSale.status }}</dd>
        </div>
        <div>
          <dt>退货</dt>
          <dd>{{ returnReceipt?.status || "尚未建立" }}</dd>
        </div>
        <div>
          <dt>退款</dt>
          <dd>{{ refund?.status || "尚未建立" }}</dd>
        </div>
      </dl>
      <nav class="after-sale-boundary__links" aria-label="售后相关入口">
        <RouterLink class="text-action" to="/after-sales">返回售后列表</RouterLink>
        <RouterLink
          class="text-action"
          :to="{ name: 'order-detail', params: { orderNo: afterSale.orderNo } }"
        >
          查看原订单
        </RouterLink>
      </nav>
    </PjSurface>
  </div>
</template>

<style scoped>
.after-sale-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.22fr) minmax(20rem, 0.68fr);
  gap: clamp(var(--pj-space-6), 5vw, var(--pj-space-9));
  align-items: start;
  margin-top: var(--pj-space-7);
}

.after-sale-main {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-6);
}

.after-sale-overview__meta,
.after-sale-state-code {
  color: inherit;
  font-size: var(--pj-font-size-sm);
}

.after-sale-journey-section {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-strong);
}

.after-sale-section-header {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--pj-space-2) var(--pj-space-4);
  align-items: baseline;
}

.after-sale-section-header p,
.after-sale-section-header span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.after-sale-section-header p,
.after-sale-section-header h2,
.after-sale-section-header span {
  margin: 0;
}

.after-sale-section-header h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.after-sale-section-header span {
  grid-column: 2;
  max-width: var(--pj-layout-reading);
}

.after-sale-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: 0;
}

.after-sale-facts div {
  min-width: 0;
}

.after-sale-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.after-sale-facts dd {
  margin: var(--pj-space-1) 0 0;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.after-sale-shipment {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-5);
}

.after-sale-shipment__intro {
  grid-column: 1 / -1;
}

.after-sale-shipment__intro strong,
.after-sale-shipment__intro p {
  display: block;
  margin: 0;
}

.after-sale-shipment__intro p {
  margin-top: var(--pj-space-1);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.after-sale-shipment > :deep(.pj-button) {
  justify-self: start;
}

.after-sale-items {
  gap: 0;
}

.after-sale-items .after-sale-section-header {
  margin-bottom: var(--pj-space-4);
}

.after-sale-item {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.after-sale-item:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.after-sale-item p,
.after-sale-item small {
  margin: var(--pj-space-1) 0 0;
  color: var(--pj-text-secondary);
}

.after-sale-item__amount {
  flex: 0 0 auto;
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  font-variant-numeric: tabular-nums;
}

.after-sale-boundary {
  position: sticky;
  top: var(--pj-space-5);
  min-width: 0;
}

.after-sale-boundary__context {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.after-sale-boundary h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.after-sale-boundary > p:not(.after-sale-boundary__context) {
  color: var(--pj-text-secondary);
}

.after-sale-boundary dl {
  margin-block: var(--pj-space-5);
}

.after-sale-boundary dl > div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.after-sale-boundary dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.after-sale-boundary__links {
  display: grid;
  justify-items: start;
  gap: var(--pj-space-3);
}

@media (max-width: 64rem) {
  .after-sale-workspace {
    grid-template-columns: minmax(0, 1fr) minmax(18rem, 0.62fr);
    gap: var(--pj-space-6);
  }
}

@media (max-width: 48rem) {
  .after-sale-workspace {
    grid-template-columns: 1fr;
  }

  .after-sale-boundary {
    position: static;
    order: -1;
  }

  .after-sale-shipment {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 32rem) {
  .after-sale-overview :deep(.pj-status-notice__actions),
  .after-sale-overview :deep(.pj-action-group) {
    width: 100%;
  }

  .after-sale-overview :deep(.pj-action-group) {
    align-items: stretch;
  }

  .after-sale-overview :deep(.pj-button),
  .after-sale-shipment > :deep(.pj-button) {
    width: 100%;
  }

  .after-sale-section-header,
  .after-sale-facts {
    grid-template-columns: 1fr;
  }

  .after-sale-section-header span {
    grid-column: 1;
  }

  .after-sale-item {
    flex-direction: column;
  }

  .after-sale-item__amount {
    justify-items: start;
  }
}
</style>
