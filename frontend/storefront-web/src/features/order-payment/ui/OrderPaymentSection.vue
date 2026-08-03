<script setup lang="ts">
import { computed, ref, watch } from "vue";

import {
  formatMoney,
  type Order,
} from "@plain-journal/foundation";
import {
  PjButton,
  PjStatusNotice,
} from "@plain-journal/ui";

import { paymentStatusPresentation } from "../model/paymentStatus";
import {
  PaymentAccessChangedError,
  usePaymentsStore,
  type PaymentAccessContext,
} from "../model/paymentStore";

const props = defineProps<{
  access: PaymentAccessContext;
  order: Order;
}>();

const emit = defineEmits<{
  boundaryState: [value: { ready: boolean; paymentMayBeInFlight: boolean }];
  paymentConfirmed: [];
}>();

const payments = usePaymentsStore();
const ready = ref(false);
const feedback = ref<{
  tone: "success" | "warning" | "processing" | "unknown";
  title: string;
  message: string;
} | null>(null);
let loadRevision = 0;

const payment = computed(() => payments.paymentForOrder(props.order.orderNo));
const paymentCopy = computed(() => payment.value
  ? paymentStatusPresentation(payment.value)
  : null);
const pendingPayment = computed(() =>
  payments.currentAccountPendingSubmission?.orderNo === props.order.orderNo
    ? payments.currentAccountPendingSubmission
    : null);
const paymentMayBeInFlight = computed(() =>
  Boolean(
    pendingPayment.value
    || payment.value?.status === "PROCESSING"
    || payment.value?.status === "SUCCESS"
    || payments.error,
  ));
const paymentTone = computed<"success" | "warning" | "processing" | "unknown">(() => {
  switch (payment.value?.status) {
    case "SUCCESS":
      return "success";
    case "FAILED":
      return "warning";
    case "PROCESSING":
      return "processing";
    default:
      return "unknown";
  }
});
const visibleError = computed(() =>
  payments.error ?? (!pendingPayment.value ? payments.submissionError : null));

function publishBoundaryState() {
  emit("boundaryState", {
    ready: ready.value,
    paymentMayBeInFlight: paymentMayBeInFlight.value,
  });
}

async function loadPayment() {
  const revision = ++loadRevision;
  ready.value = false;
  feedback.value = null;
  payments.synchronizeAccess(props.access);
  publishBoundaryState();
  try {
    await payments.loadForOrder(props.access, props.order.orderNo);
  } catch (cause) {
    if (!(cause instanceof PaymentAccessChangedError)) {
      throw cause;
    }
  } finally {
    if (revision === loadRevision) {
      ready.value = true;
      publishBoundaryState();
    }
  }
}

async function createPayment() {
  feedback.value = null;
  try {
    const value = await payments.createForOrder(props.access, props.order.orderNo);
    if (value?.status === "PROCESSING") {
      feedback.value = {
        tone: "processing",
        title: "支付单已建立",
        message: "支付单已保存，正在等待渠道返回最终结果。",
      };
    } else if (value?.status === "SUCCESS") {
      feedback.value = {
        tone: "success",
        title: "支付已确认",
        message: "支付成功事实已经确认，正在刷新订单状态。",
      };
      emit("paymentConfirmed");
    } else if (value?.status === "FAILED") {
      feedback.value = {
        tone: "warning",
        title: "支付失败已确认",
        message: "渠道已明确返回支付失败，本页不会显示支付成功。",
      };
    } else if (payments.submissionUnknown) {
      feedback.value = {
        tone: "unknown",
        title: "支付创建结果待确认",
        message: "原支付键已保留，可以查询或使用同一路径安全重试。",
      };
    }
  } catch (cause) {
    if (!(cause instanceof PaymentAccessChangedError)) {
      throw cause;
    }
  } finally {
    ready.value = true;
    publishBoundaryState();
  }
}

async function refreshPayment() {
  feedback.value = null;
  const current = payment.value;
  try {
    const value = current
      ? await payments.refreshPayment(props.access, current.paymentNo)
      : await payments.loadForOrder(props.access, props.order.orderNo, false);
    if (value?.status === "SUCCESS") {
      feedback.value = {
        tone: "success",
        title: "支付已确认",
        message: "支付成功事实已经确认，正在刷新订单状态。",
      };
      emit("paymentConfirmed");
    } else if (value?.status === "PROCESSING") {
      feedback.value = {
        tone: "processing",
        title: "支付仍在处理中",
        message: "渠道结果尚未返回，本页不会提前显示成功。",
      };
    } else if (value?.status === "FAILED") {
      feedback.value = {
        tone: "warning",
        title: "支付失败已确认",
        message: "渠道已明确返回支付失败，本页不会显示支付成功。",
      };
    }
  } catch (cause) {
    if (!(cause instanceof PaymentAccessChangedError)) {
      throw cause;
    }
  } finally {
    ready.value = true;
    publishBoundaryState();
  }
}

watch(
  [
    () => props.access.ownerId,
    () => props.access.accessToken,
    () => props.order.orderNo,
  ],
  loadPayment,
  { immediate: true },
);
watch(paymentMayBeInFlight, publishBoundaryState);
</script>

<template>
  <section class="order-journey-section payment-section" aria-labelledby="payment-title">
    <header class="journey-section-header">
      <p>付款</p>
      <h2 id="payment-title">支付状态</h2>
      <span>支付结果以已保存的支付记录为准。</span>
    </header>

    <PjStatusNotice
      v-if="payments.loadingOrderNo === order.orderNo && !payment"
      tone="processing"
      title="正在查询支付状态"
    >
      <p>页面正在读取该订单已有的支付记录。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="payment"
      :tone="paymentTone"
      :title="paymentCopy?.title ?? '支付状态待确认'"
    >
      <p>{{ paymentCopy?.detail }}</p>
      <dl class="payment-facts">
        <div>
          <dt>支付单号</dt>
          <dd>{{ payment.paymentNo }}</dd>
        </div>
        <div>
          <dt>金额</dt>
          <dd>{{ formatMoney(payment.amount) }}</dd>
        </div>
        <div>
          <dt>渠道</dt>
          <dd>{{ payment.channel }}</dd>
        </div>
        <div v-if="payment.channelTransactionNo">
          <dt>渠道流水</dt>
          <dd>{{ payment.channelTransactionNo }}</dd>
        </div>
      </dl>
      <template #actions>
        <PjButton
          variant="secondary"
          :loading="payments.refreshingPaymentNo === payment.paymentNo"
          @click="refreshPayment"
        >
          刷新支付状态
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="order.status === 'PENDING_PAYMENT'"
      :tone="pendingPayment ? 'unknown' : 'neutral'"
      :title="pendingPayment ? '支付创建结果待确认' : '尚未建立支付单'"
    >
      <p v-if="pendingPayment">{{ payments.submissionError }}</p>
      <p v-else>
        建立支付单后会进入处理中；只有渠道回调完成裁决后，页面才会显示成功或失败。
      </p>
      <template #actions>
        <PjButton
          :loading="Boolean(payments.creatingOrderNo) || payments.resolvingSubmission"
          @click="createPayment"
        >
          {{ pendingPayment ? "按原支付键查询并重试" : "创建支付单" }}
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-else
      tone="neutral"
      title="当前没有可读取的支付单"
    >
      <p>该订单状态不允许新建支付，以订单与支付记录返回的事实为准。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="feedback"
      class="payment-feedback"
      :tone="feedback.tone"
      :title="feedback.title"
    >
      <p>{{ feedback.message }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-if="visibleError"
      tone="danger"
      title="支付状态读取未完成"
      assertive
    >
      <p>{{ visibleError }}</p>
    </PjStatusNotice>
  </section>
</template>

<style scoped>
.payment-section {
  display: grid;
  gap: var(--pj-space-5);
}

.journey-section-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--pj-space-2) var(--pj-space-4);
  align-items: baseline;
  padding-bottom: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-strong);
}

.journey-section-header > p,
.journey-section-header > span {
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.journey-section-header h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.payment-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-3) var(--pj-space-5);
  margin: var(--pj-space-3) 0 0;
}

.payment-facts div {
  min-width: 0;
}

.payment-facts dt {
  color: inherit;
  font-size: var(--pj-font-size-xs);
}

.payment-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

@media (max-width: 32rem) {
  .journey-section-header {
    grid-template-columns: 1fr;
  }

  .payment-facts {
    grid-template-columns: 1fr;
  }
}
</style>
