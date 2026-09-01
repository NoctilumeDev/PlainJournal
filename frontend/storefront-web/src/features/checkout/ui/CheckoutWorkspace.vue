<script setup lang="ts">
import { computed, watch } from "vue";
import { RouterLink } from "vue-router";

import { formatMoney, multiplyMoney, type BusinessId } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import { AsyncState } from "../../../shared/ui";
import {
  type CheckoutAccessContext,
  CheckoutAccessChangedError,
  CheckoutDraftChangedError,
  useCheckoutStore,
} from "../model/checkoutStore";

const props = defineProps<{
  access: CheckoutAccessContext;
}>();
const emit = defineEmits<{
  orderConfirmed: [orderNo: string];
}>();
const checkout = useCheckoutStore();
const authorityTone = computed<"success" | "warning">(() => (
  checkout.authorityReady && !checkout.authorityHasPriceChanges
    ? "success"
    : "warning"
));

watch(
  () => [
    props.access.authenticated,
    props.access.ownerId,
    props.access.accessToken,
  ] as const,
  () => loadCheckout(),
  { immediate: true },
);

async function loadCheckout() {
  try {
    await checkout.load(props.access);
  } catch (cause) {
    if (cause instanceof CheckoutAccessChangedError) {
      return;
    }
    // The feature owns the factual, retryable read error.
  }
}

async function refreshPreview() {
  try {
    await checkout.refreshPreview(props.access);
  } catch (cause) {
    if (
      cause instanceof CheckoutAccessChangedError
      || cause instanceof CheckoutDraftChangedError
    ) {
      return;
    }
    // The previous preview is cleared; the error remains visible for retry.
  }
}

async function refreshAuthority() {
  try {
    await checkout.refreshAuthority(props.access);
  } catch (cause) {
    if (
      cause instanceof CheckoutAccessChangedError
      || cause instanceof CheckoutDraftChangedError
    ) {
      return;
    }
    // The authority error remains visible and no order can be submitted.
  }
}

async function submitOrder() {
  try {
    const order = await checkout.submitOrder(props.access);
    if (order) {
      emit("orderConfirmed", order.orderNo);
    }
  } catch (cause) {
    if (!(cause instanceof CheckoutAccessChangedError)) {
      throw cause;
    }
  }
}

async function recoverOrder() {
  try {
    const order = await checkout.recoverPendingSubmission(props.access);
    if (order) {
      emit("orderConfirmed", order.orderNo);
    }
  } catch (cause) {
    if (!(cause instanceof CheckoutAccessChangedError)) {
      throw cause;
    }
  }
}

function authorityLine(skuId: BusinessId) {
  return checkout.authority?.lines.find((line) => line.skuId === skuId) ?? null;
}
</script>

<template>
  <div class="checkout-workspace">
    <AsyncState
      :loading="checkout.loading"
      loading-message="正在读取地址、账户购物车与可用权益…"
      :error="checkout.error"
      error-eyebrow="草稿未完成"
      error-title="结算事实没有被读取为成功。"
      retry-label="重新读取结算事实"
      @retry="loadCheckout"
    >
      <PjSurface
        v-if="checkout.cart.selectedItems.length === 0"
        class="checkout-empty"
        tone="soft"
        padding="large"
      >
        <p>没有待结算商品</p>
        <h2>账户购物车中没有已选中的商品。</h2>
        <RouterLink class="primary-action" to="/bag">返回购物袋核对 →</RouterLink>
      </PjSurface>

      <div v-else class="checkout-journey">
        <div class="checkout-journey__main">
          <PjSurface
            as="section"
            class="transaction-section"
            tone="plain"
            padding="none"
            aria-labelledby="checkout-address-title"
          >
            <header>
              <p class="transaction-kicker">01 / 收货信息</p>
              <h2 id="checkout-address-title">选择订单收货地址</h2>
            </header>
            <PjStatusNotice
              v-if="checkout.addresses.addresses.length === 0"
              tone="warning"
              title="需要完整收货地址"
            >
              <p>营销地区资格需要完整地址代码，先保存一个收货地址。</p>
              <RouterLink class="primary-action" to="/account/addresses">
                添加收货地址 →
              </RouterLink>
            </PjStatusNotice>
            <label
              v-for="address in checkout.addresses.addresses"
              :key="address.id"
              class="transaction-choice"
            >
              <input
                type="radio"
                name="checkout-address"
                :value="address.id"
                :checked="checkout.selectedAddressId === address.id"
                @change="checkout.selectAddress(address.id)"
              />
              <span>
                <strong>
                  {{ address.recipientName }}
                  <small v-if="address.defaultAddress">默认</small>
                </strong>
                <span>
                  {{ address.province }} {{ address.city }} {{ address.district }}
                  {{ address.detailAddress }} · {{ address.phone }}
                </span>
              </span>
            </label>
            <RouterLink class="text-action" to="/account/addresses">管理收货信息</RouterLink>
          </PjSurface>

          <PjSurface
            as="section"
            class="transaction-section"
            tone="plain"
            padding="none"
            aria-labelledby="checkout-benefit-title"
          >
            <header>
              <p class="transaction-kicker">02 / 优惠权益</p>
              <h2 id="checkout-benefit-title">选择要用于订单的权益</h2>
            </header>
            <p class="transaction-note">
              同一类型最多选择一个。权威核对仍不锁权益，订单提交后由 Trade 正式锁定。
            </p>
            <PjStatusNotice
              v-if="checkout.availableBenefits.length === 0"
              tone="neutral"
              title="当前没有可用权益"
            >
              <p>仍可按原价继续核对结算草稿。</p>
            </PjStatusNotice>
            <label
              v-for="benefit in checkout.availableBenefits"
              :key="benefit.benefitNo"
              class="transaction-choice"
            >
              <input
                type="checkbox"
                :checked="checkout.selectedBenefitNos.includes(benefit.benefitNo)"
                @change="checkout.toggleBenefit(benefit)"
              />
              <span>
                <strong>
                  {{ benefit.ruleCode }}
                  <small>{{ benefit.benefitType }}</small>
                </strong>
                <span>
                  满 {{ formatMoney(benefit.thresholdAmount) }}
                  可减 {{ formatMoney(benefit.discountAmount) }}
                </span>
              </span>
            </label>
          </PjSurface>

          <PjSurface
            as="section"
            class="transaction-section"
            tone="plain"
            padding="none"
            aria-labelledby="checkout-items-title"
          >
            <header>
              <p class="transaction-kicker">03 / 商品</p>
              <h2 id="checkout-items-title">账户购物车快照</h2>
            </header>
            <article
              v-for="item in checkout.cart.selectedItems"
              :key="item.id"
              class="transaction-line"
            >
              <div>
                <strong>{{ item.productTitle }}</strong>
                <p>{{ item.skuName }} · {{ item.quantity }} 件</p>
                <p v-if="authorityLine(item.skuId)" class="transaction-authority-line">
                  实时单价
                  {{ formatMoney(authorityLine(item.skuId)?.currentUnitPrice) }}
                  · 可用 {{ authorityLine(item.skuId)?.available }} 件
                  <strong v-if="authorityLine(item.skuId)?.priceChanged">价格已变化</strong>
                </p>
              </div>
              <strong>
                {{
                  formatMoney(multiplyMoney(
                    authorityLine(item.skuId)?.currentUnitPrice ?? item.unitPrice,
                    item.quantity,
                  ))
                }}
              </strong>
            </article>
          </PjSurface>
        </div>

        <PjSurface
          as="aside"
          class="checkout-sidebar"
          tone="raised"
          padding="large"
          aria-labelledby="checkout-summary-title"
        >
          <p class="transaction-kicker">服务端试算</p>
          <h2 id="checkout-summary-title">金额明细</h2>
          <dl class="checkout-amounts">
            <div>
              <dt>商品原价</dt>
              <dd>{{ formatMoney(checkout.displayPreview?.originalAmount ?? checkout.originalAmount) }}</dd>
            </div>
            <div>
              <dt>优惠券</dt>
              <dd>− {{ formatMoney(checkout.displayPreview?.couponDiscount ?? "0.00") }}</dd>
            </div>
            <div>
              <dt>红包</dt>
              <dd>− {{ formatMoney(checkout.displayPreview?.redPacketDiscount ?? "0.00") }}</dd>
            </div>
            <div>
              <dt>平台补贴</dt>
              <dd>− {{ formatMoney(checkout.displayPreview?.subsidyDiscount ?? "0.00") }}</dd>
            </div>
            <div class="checkout-amounts__total">
              <dt>试算应付</dt>
              <dd>{{ formatMoney(checkout.displayPreview?.payableAmount ?? checkout.originalAmount) }}</dd>
            </div>
          </dl>

          <PjStatusNotice
            v-if="checkout.previewError"
            class="checkout-sidebar__notice"
            tone="danger"
            title="金额试算未完成"
            assertive
          >
            <p>{{ checkout.previewError }}</p>
          </PjStatusNotice>
          <PjStatusNotice
            v-else-if="checkout.displayPreview"
            class="checkout-sidebar__notice"
            tone="neutral"
            title="当前金额仅为试算"
          >
            <p>
              试算时间
              {{ new Date(checkout.displayPreview.calculatedAt).toLocaleString("zh-CN") }}。
              未写入 pricing_lock，也未改变权益状态。
            </p>
          </PjStatusNotice>

          <PjButton
            block
            :disabled="!checkout.readyForPreview"
            :loading="checkout.previewing"
            @click="refreshPreview"
          >
            {{ checkout.previewing ? "正在等待 Marketing 返回…" : "重新试算金额 →" }}
          </PjButton>

          <PjStatusNotice
            v-if="checkout.authorityError"
            class="checkout-sidebar__notice"
            tone="danger"
            title="实时事实核对未完成"
            assertive
          >
            <p>{{ checkout.authorityError }}</p>
          </PjStatusNotice>
          <PjStatusNotice
            v-else-if="checkout.authority"
            class="checkout-sidebar__notice"
            :tone="authorityTone"
            title="权威核对已完成"
          >
            <p>
              Catalog、Inventory 与 Marketing 核对时间
              {{ new Date(checkout.authority.checkedAt).toLocaleString("zh-CN") }}。
            </p>
            <p v-if="checkout.authorityHasPriceChanges">
              购物车价格已经变化，订单将以这里显示的实时价格为准。
            </p>
            <p v-if="!checkout.authorityReady">
              至少一个 SKU 的可用库存不足，当前不能提交。
            </p>
          </PjStatusNotice>

          <PjButton
            block
            variant="secondary"
            :disabled="!checkout.readyForPreview"
            :loading="checkout.authorityChecking"
            @click="refreshAuthority"
          >
            {{
              checkout.authorityChecking
                ? "正在核对 Catalog、Inventory 与 Marketing…"
                : "核对实时价格、库存与优惠"
            }}
          </PjButton>

          <PjStatusNotice
            v-if="checkout.submissionError && !checkout.pendingSubmission"
            class="checkout-sidebar__notice"
            :tone="checkout.submissionUnknown ? 'unknown' : 'danger'"
            :title="checkout.submissionUnknown ? '订单结果尚未确认' : '订单提交未完成'"
            :assertive="!checkout.submissionUnknown"
          >
            <p>{{ checkout.submissionError }}</p>
          </PjStatusNotice>

          <PjStatusNotice
            v-if="checkout.pendingSubmission"
            class="checkout-sidebar__notice checkout-pending"
            tone="unknown"
            title="订单结果尚未确认"
          >
            <p v-if="checkout.submissionError">{{ checkout.submissionError }}</p>
            <strong>请求键与固定载荷已保留</strong>
            <p>
              不会生成新键。可以先查询 Trade；仍未找到时，使用原请求安全重试。
            </p>
            <PjActionGroup class="checkout-pending__actions">
              <PjButton
                variant="secondary"
                :loading="checkout.resolvingSubmission"
                :disabled="checkout.submitting"
                @click="recoverOrder"
              >
                {{ checkout.resolvingSubmission ? "正在查询…" : "查询订单结果" }}
              </PjButton>
              <PjButton
                :loading="checkout.submitting"
                :disabled="checkout.resolvingSubmission"
                @click="submitOrder"
              >
                {{ checkout.submitting ? "正在安全重试…" : "使用原请求安全重试" }}
              </PjButton>
            </PjActionGroup>
          </PjStatusNotice>

          <RouterLink
            v-if="checkout.lastOrder"
            class="text-action checkout-confirmed-order"
            :to="{ name: 'order-detail', params: { orderNo: checkout.lastOrder.orderNo } }"
          >
            查看已确认订单 {{ checkout.lastOrder.orderNo }} →
          </RouterLink>

          <PjButton
            v-if="!checkout.pendingSubmission"
            block
            :disabled="!checkout.authorityReady"
            :loading="checkout.submitting"
            @click="submitOrder"
          >
            {{ checkout.submitting ? "正在等待 Trade 确认…" : "以当前事实提交订单 →" }}
          </PjButton>

          <PjStatusNotice
            class="checkout-boundary-notice"
            tone="neutral"
            title="订单成功不等于支付成功"
          >
            <p>
              Trade 会再次读取 Catalog 与地址、正式锁定 Marketing 权益，并以 Inventory
              的 MySQL 预占结果裁决库存。网络中断时页面只显示结果未知并查询恢复；
              订单确认后可在详情页创建或查询独立 Payment 支付单。
            </p>
          </PjStatusNotice>
        </PjSurface>
      </div>
    </AsyncState>
  </div>
</template>

<style scoped>
.checkout-empty {
  max-width: var(--pj-layout-reading);
}

.checkout-empty > p:first-child,
.transaction-kicker {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.checkout-empty h2,
.transaction-section h2,
.checkout-sidebar h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 550;
}

.checkout-empty h2 {
  margin-bottom: var(--pj-space-5);
}

.checkout-journey {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(22rem, 0.85fr);
  gap: clamp(2rem, 5vw, 6rem);
  align-items: start;
}

.checkout-journey__main {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
}

.transaction-section {
  min-width: 0;
  padding-top: var(--pj-space-5);
  border: 0;
  border-top: 1px solid var(--pj-text-primary);
  background: transparent;
}

.transaction-section > header {
  margin-bottom: var(--pj-space-5);
}

.transaction-note {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.transaction-choice {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
  cursor: pointer;
}

.transaction-choice:last-of-type {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.transaction-choice > input {
  margin-top: 0.25rem;
  accent-color: var(--pj-action-primary);
}

.transaction-choice > span,
.transaction-choice strong {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-2);
}

.transaction-choice strong {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.transaction-choice small {
  color: var(--pj-brand-primary-hover);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.06em;
}

.transaction-choice > span > span {
  color: var(--pj-text-secondary);
  overflow-wrap: anywhere;
}

.transaction-line {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.transaction-line:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.transaction-line p {
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.transaction-authority-line {
  font-size: var(--pj-font-size-sm);
}

.transaction-authority-line strong {
  display: inline;
  margin-left: var(--pj-space-2);
  color: var(--pj-status-warning-text);
}

.checkout-sidebar {
  position: sticky;
  top: var(--pj-space-5);
  min-width: 0;
}

.checkout-amounts {
  margin-block: var(--pj-space-5);
}

.checkout-amounts > div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.checkout-amounts dd {
  margin: 0;
  text-align: end;
}

.checkout-amounts__total {
  margin-top: var(--pj-space-3);
  font-size: var(--pj-font-size-lg);
  font-weight: 650;
}

.checkout-sidebar__notice,
.checkout-confirmed-order,
.checkout-boundary-notice {
  margin-top: var(--pj-space-4);
}

.checkout-sidebar > .pj-button {
  margin-top: var(--pj-space-4);
}

.checkout-pending__actions {
  margin-top: var(--pj-space-4);
}

.checkout-confirmed-order {
  display: inline-flex;
}

.checkout-boundary-notice {
  margin-top: var(--pj-space-6);
}

@media (max-width: 64rem) {
  .checkout-journey {
    grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.75fr);
    gap: var(--pj-space-6);
  }
}

@media (max-width: 48rem) {
  .checkout-journey {
    grid-template-columns: 1fr;
  }

  .checkout-sidebar {
    position: static;
  }
}

@media (max-width: 32rem) {
  .transaction-choice strong {
    grid-template-columns: 1fr;
  }

  .transaction-line,
  .checkout-amounts > div {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--pj-space-2);
  }

  .checkout-amounts dd {
    text-align: start;
  }
}
</style>
