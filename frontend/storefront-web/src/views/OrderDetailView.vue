<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { formatMoney } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  useAfterSalesStore,
  type AfterSaleAccessContext,
} from "../entities/after-sale";
import {
  orderStatusPresentation,
  useOrdersStore,
  type OrderAccessContext,
} from "../entities/order";
import type { ReviewAccessContext } from "../entities/product-review";
import { OrderReviewSection } from "../features/order-review";
import {
  OrderFulfillmentSection,
  type FulfillmentAccessContext,
} from "../features/order-fulfillment";
import {
  OrderPaymentSection,
  type PaymentAccessContext,
} from "../features/order-payment";
import { useSessionStore } from "../features/customer-session";

const route = useRoute();
const session = useSessionStore();
const orders = useOrdersStore();
const afterSales = useAfterSalesStore();
const confirmingCancellation = ref(false);
const cancellationFeedback = ref<{
  tone: "success" | "warning";
  title: string;
  message: string;
} | null>(null);
const paymentBoundaryReady = ref(false);
const paymentMayBeInFlight = ref(true);
const afterSaleFeedback = ref<string | null>(null);
const afterSaleReason = ref("");
const orderNo = computed(() => String(route.params.orderNo ?? ""));
const orderAccess = computed<OrderAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const paymentAccess = computed<PaymentAccessContext>(() => orderAccess.value);
const fulfillmentAccess = computed<FulfillmentAccessContext>(() => orderAccess.value);
const afterSaleAccess = computed<AfterSaleAccessContext>(() => orderAccess.value);
const reviewAccess = computed<ReviewAccessContext>(() => orderAccess.value);
const order = computed(() => orders.order(orderNo.value));
const afterSale = computed(() => afterSales.forOrder(orderNo.value));
const statusCopy = computed(() => order.value
  ? orderStatusPresentation(order.value)
  : null);
const statusTone = computed<"neutral" | "success" | "warning" | "processing" | "attention">(() => {
  switch (order.value?.status) {
    case "PAID":
    case "COMPLETED":
      return "success";
    case "CANCELING":
      return "warning";
    case "PAYMENT_EXCEPTION":
      return "attention";
    case "PENDING_STOCK":
    case "PENDING_PAYMENT":
    case "FULFILLING":
    case "SHIPPED":
      return "processing";
    default:
      return "neutral";
  }
});
const nextStep = computed<{ href: string; label: string } | null>(() => {
  switch (order.value?.status) {
    case "PENDING_PAYMENT":
      return { href: "#payment-title", label: "处理支付" };
    case "PAID":
    case "FULFILLING":
    case "SHIPPED":
      return { href: "#fulfillment-title", label: "查看配送进度" };
    case "COMPLETED":
      return { href: "#order-review-title", label: "评价本次购买" };
    case "PAYMENT_EXCEPTION":
      return { href: "#payment-title", label: "查看待核对事实" };
    default:
      return null;
  }
});
const pendingCancellation = computed(() =>
  orders.currentAccountPendingCancellation?.orderNo === orderNo.value
    ? orders.currentAccountPendingCancellation
    : null);
const canCancel = computed(() =>
  order.value?.status === "PENDING_PAYMENT"
  && paymentBoundaryReady.value
  && !paymentMayBeInFlight.value);
const orderMayHaveFulfillment = computed(() =>
  Boolean(order.value && [
    "PAID",
    "FULFILLING",
    "SHIPPED",
    "COMPLETED",
  ].includes(order.value.status)));
const canApplyAfterSale = computed(() =>
  order.value?.status === "COMPLETED" && !afterSale.value);

async function loadOrder() {
  if (!orderNo.value) {
    orders.error = "订单编号缺失。";
    return;
  }
  confirmingCancellation.value = false;
  cancellationFeedback.value = null;
  paymentBoundaryReady.value = false;
  paymentMayBeInFlight.value = true;
  afterSaleFeedback.value = null;
  const value = await orders.loadOrder(orderAccess.value, orderNo.value);
  if (value) {
    if (value.status === "COMPLETED") {
      await afterSales.load(afterSaleAccess.value);
    }
  }
}

async function confirmCancellation() {
  confirmingCancellation.value = false;
  cancellationFeedback.value = null;
  const value = await orders.cancelOrder(orderAccess.value, orderNo.value);
  if (value?.status === "CANCELED") {
    cancellationFeedback.value = {
      tone: "success",
      title: "订单取消已完成",
      message: "订单已确认取消，库存与营销权益释放流程已经收敛。",
    };
  } else if (value?.status === "CANCELING") {
    cancellationFeedback.value = {
      tone: "warning",
      title: "订单仍在取消",
      message: "取消请求已经保存，库存与营销权益仍在释放，完成前不会显示已取消。",
    };
  }
}

function updatePaymentBoundary(value: {
  ready: boolean;
  paymentMayBeInFlight: boolean;
}) {
  paymentBoundaryReady.value = value.ready;
  paymentMayBeInFlight.value = value.paymentMayBeInFlight;
}

async function handlePaymentConfirmed() {
  await orders.loadOrder(orderAccess.value, orderNo.value);
}

async function handleReceiptConfirmed() {
  await orders.loadOrder(orderAccess.value, orderNo.value);
}

async function applyAfterSale() {
  afterSaleFeedback.value = null;
  const reason = afterSaleReason.value.trim();
  if (!reason) {
    afterSaleFeedback.value = "请说明申请整单退货退款的真实原因。";
    return;
  }
  const value = await afterSales.apply(afterSaleAccess.value, orderNo.value, reason);
  if (value) {
    afterSaleReason.value = "";
    afterSaleFeedback.value = "Trade 已保存售后申请，请在售后详情中查看审核、退货与退款进度。";
  } else if (afterSales.applicationUnknown) {
    afterSaleFeedback.value = "售后申请结果尚未确认，原申请键已保留。";
  }
}

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

watch(orderNo, loadOrder, { immediate: true });
</script>

<template>
  <PjPageContainer as="section" class="order-detail-page">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/orders">我的订单</RouterLink>
      <span aria-hidden="true">/</span>
      <span>订单详情</span>
    </nav>

    <header class="order-page-header">
      <div>
        <p>订单 {{ orderNo }}</p>
        <h1>订单详情</h1>
      </div>
      <RouterLink class="text-action" to="/orders">返回订单列表</RouterLink>
    </header>

    <PjStatusNotice
      v-if="orders.loading && !order"
      tone="processing"
      title="正在读取订单"
    >
      <p>页面正在查询这笔订单的最新状态与不可变快照。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="orders.error && !order"
      tone="danger"
      title="订单读取未完成"
      assertive
    >
      <p>{{ orders.error }}</p>
      <template #actions>
        <PjButton variant="secondary" @click="loadOrder">重新查询订单</PjButton>
      </template>
    </PjStatusNotice>

    <template v-else-if="order">
      <PjStatusNotice
        class="order-overview"
        :tone="statusTone"
        :title="statusCopy?.title ?? '订单状态待确认'"
      >
        <p>{{ statusCopy?.detail }}</p>
        <p class="order-overview__meta">
          {{ statusCopy?.label }} · 更新于 {{ formatTimestamp(order.updatedAt) }}
        </p>
        <template #actions>
          <PjActionGroup class="order-overview__actions">
            <a v-if="nextStep" class="text-action" :href="nextStep.href">
              {{ nextStep.label }}
            </a>
            <PjButton
              variant="secondary"
              :loading="orders.loading"
              @click="loadOrder"
            >
              刷新订单状态
            </PjButton>
            <PjButton
              v-if="canCancel"
              variant="text"
              class="text-action--danger"
              :disabled="Boolean(orders.cancelingOrderNo)"
              @click="confirmingCancellation = true"
            >
              取消订单
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>

      <PjStatusNotice
        v-if="confirmingCancellation && canCancel"
        class="order-cancel-confirmation"
        tone="danger"
        title="确认取消订单"
        assertive
      >
        <p>取消后将释放库存与营销权益。确定不再支付这笔订单吗？</p>
        <template #actions>
          <PjActionGroup>
            <PjButton
              variant="destructive"
              :loading="Boolean(orders.cancelingOrderNo)"
              @click="confirmCancellation"
            >
              确认取消订单
            </PjButton>
            <PjButton
              variant="text"
              :disabled="Boolean(orders.cancelingOrderNo)"
              @click="confirmingCancellation = false"
            >
              保留订单
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>

      <PjStatusNotice
        v-if="pendingCancellation"
        tone="unknown"
        title="取消结果待确认"
      >
        <p>{{ orders.cancellationError }}</p>
        <template #actions>
          <PjButton
            variant="secondary"
            :loading="orders.resolvingCancellation || Boolean(orders.cancelingOrderNo)"
            @click="confirmCancellation"
          >
            按原路径查询并重试
          </PjButton>
        </template>
      </PjStatusNotice>

      <PjStatusNotice
        v-if="cancellationFeedback"
        :tone="cancellationFeedback.tone"
        :title="cancellationFeedback.title"
      >
        <p>{{ cancellationFeedback.message }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="orders.error"
        tone="danger"
        title="订单刷新未完成"
        assertive
      >
        <p>{{ orders.error }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="orders.cancellationError && !pendingCancellation"
        tone="danger"
        title="取消状态读取未完成"
        assertive
      >
        <p>{{ orders.cancellationError }}</p>
      </PjStatusNotice>
      <PjStatusNotice
        v-if="order.status === 'PENDING_PAYMENT' && (!paymentBoundaryReady || paymentMayBeInFlight)"
        tone="processing"
        title="支付与取消保持互斥"
      >
        <p>支付单正在处理或结果尚未确认。为避免并发，本页暂不开放顾客取消。</p>
      </PjStatusNotice>

      <div class="order-detail-layout">
        <div class="order-detail-main">
          <section class="order-fact-section" aria-labelledby="order-items-title">
            <header>
              <p>本次购买</p>
              <h2 id="order-items-title">订单商品</h2>
            </header>
            <article v-for="item in order.items" :key="item.lineNo" class="order-line">
              <div>
                <strong>{{ item.productTitle }}</strong>
                <p>{{ item.skuName }} · {{ item.quantity }} 件</p>
                <small>SKU {{ item.skuCode }} · 单价 {{ formatMoney(item.unitPrice) }}</small>
              </div>
              <div class="order-line__amount">
                <strong>{{ formatMoney(item.payableAmount) }}</strong>
                <small>原始 {{ formatMoney(item.lineAmount) }}</small>
              </div>
            </article>
          </section>

          <section class="order-fact-section" aria-labelledby="order-address-title">
            <header>
              <p>配送地址</p>
              <h2 id="order-address-title">收货信息</h2>
            </header>
            <address class="order-address">
              <strong>{{ order.deliveryAddress.recipientName }}</strong>
              <span>{{ order.deliveryAddress.phone }}</span>
              <span>
                {{ order.deliveryAddress.province }}
                {{ order.deliveryAddress.city }}
                {{ order.deliveryAddress.district }}
                {{ order.deliveryAddress.detailAddress }}
              </span>
              <span v-if="order.deliveryAddress.postalCode">
                邮编 {{ order.deliveryAddress.postalCode }}
              </span>
            </address>
          </section>

          <OrderPaymentSection
            :access="paymentAccess"
            :order="order"
            @boundary-state="updatePaymentBoundary"
            @payment-confirmed="handlePaymentConfirmed"
          />

          <OrderFulfillmentSection
            v-if="orderMayHaveFulfillment"
            :access="fulfillmentAccess"
            :order="order"
            @receipt-confirmed="handleReceiptConfirmed"
          />

          <OrderReviewSection
            v-if="order.status === 'COMPLETED'"
            :access="reviewAccess"
            :order-no="order.orderNo"
          />

          <section
            v-if="order.status === 'COMPLETED' || afterSale"
            class="order-journey-section after-sale-entry"
            aria-labelledby="after-sale-entry-title"
          >
            <header class="order-section-header">
              <p>售后</p>
              <h2 id="after-sale-entry-title">整单退货退款</h2>
            </header>

            <PjStatusNotice
              v-if="afterSale"
              tone="neutral"
              title="当前订单已有售后申请"
            >
              <p>{{ afterSale.afterSaleNo }} · {{ afterSale.status }}</p>
              <template #actions>
                <RouterLink
                  class="text-action"
                  :to="{
                    name: 'after-sale-detail',
                    params: { afterSaleNo: afterSale.afterSaleNo },
                  }"
                >
                  查看退货与退款进度
                </RouterLink>
              </template>
            </PjStatusNotice>

            <PjSurface
              v-else-if="canApplyAfterSale"
              as="form"
              tone="plain"
              padding="medium"
              class="after-sale-form"
              @submit.prevent="applyAfterSale"
            >
              <p>
                首版只支持已完成订单的整单退货退款；同一订单最多一笔售后。
                审核通过后才会建立退货单。
              </p>
              <label>
                申请原因
                <textarea
                  v-model.trim="afterSaleReason"
                  required
                  maxlength="500"
                  rows="4"
                  placeholder="请说明商品状态、实际问题与退货原因"
                ></textarea>
              </label>
              <PjButton
                type="submit"
                :loading="afterSales.applyingOrderNo === order.orderNo"
              >
                {{
                  afterSales.currentPending?.orderNo === order.orderNo
                    ? "按原申请键查询并重试"
                    : "提交整单售后申请"
                }}
              </PjButton>
            </PjSurface>

            <PjStatusNotice
              v-if="afterSaleFeedback"
              tone="success"
              title="售后事实已更新"
            >
              <p>{{ afterSaleFeedback }}</p>
            </PjStatusNotice>
            <PjStatusNotice
              v-if="afterSales.applicationError"
              tone="danger"
              title="售后申请未完成"
              assertive
            >
              <p>{{ afterSales.applicationError }}</p>
            </PjStatusNotice>
          </section>
        </div>

        <PjSurface
          as="aside"
          tone="soft"
          padding="large"
          class="order-summary"
        >
          <p class="order-summary__context">不可变价格快照</p>
          <h2>金额明细</h2>
          <dl>
            <div>
              <dt>商品原价</dt>
              <dd>{{ formatMoney(order.priceSnapshot?.originalAmount ?? order.totalAmount) }}</dd>
            </div>
            <div>
              <dt>优惠券</dt>
              <dd>− {{ formatMoney(order.priceSnapshot?.couponDiscount ?? "0.00") }}</dd>
            </div>
            <div>
              <dt>红包</dt>
              <dd>− {{ formatMoney(order.priceSnapshot?.redPacketDiscount ?? "0.00") }}</dd>
            </div>
            <div>
              <dt>平台补贴</dt>
              <dd>− {{ formatMoney(order.priceSnapshot?.subsidyDiscount ?? "0.00") }}</dd>
            </div>
            <div class="order-summary__total">
              <dt>订单应付</dt>
              <dd>{{ formatMoney(order.totalAmount) }}</dd>
            </div>
          </dl>
          <details v-if="order.priceSnapshot" class="order-trace">
            <summary>查看价格追溯信息</summary>
            <p>
              定价版本 {{ order.priceSnapshot.pricingVersion }} ·
              锁定编号 {{ order.priceSnapshot.marketingLockNo }}
            </p>
          </details>
          <PjStatusNotice
            v-else
            tone="processing"
            title="价格快照正在形成"
          >
            <p>订单仍处于异步推进状态。</p>
          </PjStatusNotice>
          <div class="order-boundary">
            <strong>事实边界</strong>
            <p>
              支付、履约与退款会读取各自保存的记录。任何结果未确认时，
              页面都不会提前显示成功。
            </p>
          </div>
          <nav class="order-summary__links" aria-label="订单后续入口">
            <RouterLink class="text-action" to="/products">继续浏览商品</RouterLink>
            <RouterLink class="text-action" to="/orders">返回我的订单</RouterLink>
          </nav>
        </PjSurface>
      </div>
    </template>
  </PjPageContainer>
</template>

<style scoped>
.order-detail-page {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.order-page-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.order-page-header p {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
  overflow-wrap: anywhere;
}

.order-page-header h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
}

.order-overview,
.order-cancel-confirmation {
  margin-bottom: var(--pj-space-5);
}

.order-overview__meta {
  color: inherit;
  font-size: var(--pj-font-size-sm);
}

.order-detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.22fr) minmax(21rem, 0.68fr);
  gap: clamp(2rem, 5vw, 6rem);
  align-items: start;
  margin-top: var(--pj-space-7);
}

.order-detail-main {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
}

.order-fact-section,
.order-journey-section {
  min-width: 0;
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-strong);
}

.order-fact-section > header,
.order-section-header {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--pj-space-2) var(--pj-space-4);
  align-items: baseline;
  margin-bottom: var(--pj-space-5);
}

.order-fact-section > header p,
.order-section-header p {
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.order-fact-section h2,
.order-section-header h2,
.order-summary h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.order-line {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.order-line:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.order-line p,
.order-line small {
  margin: var(--pj-space-1) 0 0;
  color: var(--pj-text-secondary);
}

.order-line__amount {
  flex: 0 0 auto;
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  font-variant-numeric: tabular-nums;
}

.order-address {
  display: grid;
  gap: var(--pj-space-2);
  padding-block: var(--pj-space-4);
  border-block: 1px solid var(--pj-border-subtle);
  font-style: normal;
}

.order-address span {
  color: var(--pj-text-secondary);
}

.after-sale-form {
  display: grid;
  gap: var(--pj-space-4);
}

.after-sale-form p {
  margin: 0;
  color: var(--pj-text-secondary);
}

.after-sale-form label {
  display: grid;
  gap: var(--pj-space-2);
}

.after-sale-form textarea {
  width: 100%;
  padding: var(--pj-space-3);
  border: 1px solid var(--pj-border-strong);
  background: var(--pj-surface-default);
  color: var(--pj-text-primary);
  resize: vertical;
}

.order-summary {
  position: sticky;
  top: var(--pj-space-5);
  min-width: 0;
}

.order-summary__context {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.order-summary dl {
  margin-block: var(--pj-space-5);
}

.order-summary dl > div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.order-summary dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.order-summary__total {
  margin-top: var(--pj-space-3);
  color: var(--pj-text-primary);
  font-size: var(--pj-font-size-lg);
  font-weight: 650;
}

.order-trace {
  padding-block: var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.order-trace summary {
  color: var(--pj-text-primary);
  cursor: pointer;
}

.order-trace p {
  margin-bottom: 0;
  overflow-wrap: anywhere;
}

.order-boundary {
  margin-top: var(--pj-space-5);
  padding-left: var(--pj-space-4);
  border-left: 0.2rem solid var(--pj-status-warning-line);
}

.order-boundary p {
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.order-summary__links {
  display: grid;
  justify-items: start;
  gap: var(--pj-space-3);
  margin-top: var(--pj-space-6);
}

@media (max-width: 64rem) {
  .order-detail-layout {
    grid-template-columns: minmax(0, 1fr) minmax(19rem, 0.68fr);
    gap: var(--pj-space-6);
  }
}

@media (max-width: 48rem) {
  .order-page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .order-detail-layout {
    grid-template-columns: 1fr;
  }

  .order-summary {
    position: static;
    order: -1;
  }
}

@media (max-width: 32rem) {
  .order-detail-page {
    padding-top: var(--pj-space-5);
  }

  .order-overview :deep(.pj-status-notice__actions) {
    width: 100%;
  }

  .order-overview :deep(.pj-action-group) {
    width: 100%;
    align-items: stretch;
  }

  .order-overview :deep(.pj-button) {
    width: 100%;
  }

  .order-line {
    flex-direction: column;
  }

  .order-line__amount {
    justify-items: start;
  }

  .order-fact-section > header,
  .order-section-header {
    grid-template-columns: 1fr;
  }
}
</style>
