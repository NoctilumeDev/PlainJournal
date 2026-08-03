<script setup lang="ts">
import { computed, ref, watch } from "vue";

import type { Order } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjResponsiveImage,
  PjStatusNotice,
  type PjResponsiveImageSource,
} from "@plain-journal/ui";

import fulfillmentAvif640 from "../../../assets/fulfillment/qinghe-parcel-route-640.avif";
import fulfillmentAvif1024 from "../../../assets/fulfillment/qinghe-parcel-route-1024.avif";
import fulfillmentAvif1672 from "../../../assets/fulfillment/qinghe-parcel-route-1672.avif";
import fulfillmentIllustration from "../../../assets/fulfillment/qinghe-parcel-route.png";
import fulfillmentWebp640 from "../../../assets/fulfillment/qinghe-parcel-route-640.webp";
import fulfillmentWebp1024 from "../../../assets/fulfillment/qinghe-parcel-route-1024.webp";
import fulfillmentWebp1672 from "../../../assets/fulfillment/qinghe-parcel-route-1672.webp";
import {
  FulfillmentAccessChangedError,
  fulfillmentHistoryLabel,
  fulfillmentStatusPresentation,
  useFulfillmentsStore,
  type FulfillmentAccessContext,
} from "../../../entities/fulfillment";
import { useReceiptConfirmationStore } from "../model/receiptConfirmationStore";

const props = defineProps<{
  access: FulfillmentAccessContext;
  order: Order;
}>();

const emit = defineEmits<{
  receiptConfirmed: [];
}>();

const fulfillments = useFulfillmentsStore();
const confirmations = useReceiptConfirmationStore();
const confirmingReceipt = ref(false);
const feedback = ref<{
  tone: "success" | "processing";
  title: string;
  message: string;
} | null>(null);
let loadRevision = 0;
const fulfillmentImageSources: PjResponsiveImageSource[] = [
  {
    type: "image/avif",
    srcset: [
      `${fulfillmentAvif640} 640w`,
      `${fulfillmentAvif1024} 1024w`,
      `${fulfillmentAvif1672} 1672w`,
    ].join(", "),
  },
  {
    type: "image/webp",
    srcset: [
      `${fulfillmentWebp640} 640w`,
      `${fulfillmentWebp1024} 1024w`,
      `${fulfillmentWebp1672} 1672w`,
    ].join(", "),
  },
];

const fulfillment = computed(() =>
  fulfillments.fulfillmentForOrder(props.order.orderNo));
const shipmentPosition = computed(() =>
  fulfillments.positionForOrder(props.order.orderNo));
const fulfillmentCopy = computed(() => fulfillment.value
  ? fulfillmentStatusPresentation(fulfillment.value)
  : null);
const fulfillmentTone = computed(() => {
  switch (fulfillmentCopy.value?.tone) {
    case "success":
      return "success";
    case "warning":
      return "warning";
    case "muted":
      return "neutral";
    default:
      return "processing";
  }
});
const canConfirmReceipt = computed(() =>
  Boolean(fulfillment.value && [
    "SHIPPED",
    "IN_TRANSIT",
    "DELIVERING",
  ].includes(fulfillment.value.status)));
const confirmationUnknown = computed(() =>
  confirmations.unknownOrderNo === props.order.orderNo);

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

function formatCoordinate(value: string | number): string {
  return Number(value).toFixed(4);
}

async function loadFulfillment() {
  const revision = ++loadRevision;
  confirmingReceipt.value = false;
  feedback.value = null;
  confirmations.synchronizeAccess(props.access);
  try {
    const [value] = await Promise.all([
      fulfillments.loadForOrder(props.access, props.order.orderNo),
      fulfillments.loadPosition(props.access, props.order.orderNo),
    ]);
    if (revision === loadRevision) {
      confirmations.resolveFromFact(props.access, value);
    }
  } catch (cause) {
    if (!(cause instanceof FulfillmentAccessChangedError)) {
      throw cause;
    }
  }
}

async function refreshFulfillment() {
  feedback.value = null;
  try {
    const value = await fulfillments.loadForOrder(
      props.access,
      props.order.orderNo,
      false,
    );
    await fulfillments.loadPosition(props.access, props.order.orderNo);
    confirmations.resolveFromFact(props.access, value);
    if (value?.status === "SIGNED") {
      feedback.value = {
        tone: "success",
        title: "签收事实已确认",
        message: "签收记录已经确认，正在刷新订单完成状态。",
      };
      emit("receiptConfirmed");
    } else if (value) {
      feedback.value = {
        tone: "processing",
        title: "配送事实已更新",
        message: `当前配送状态为 ${value.status}，未到签收状态时不会显示完成。`,
      };
    }
  } catch (cause) {
    if (!(cause instanceof FulfillmentAccessChangedError)) {
      throw cause;
    }
  }
}

async function confirmReceipt() {
  confirmingReceipt.value = false;
  feedback.value = null;
  try {
    const value = await confirmations.confirmReceipt(
      props.access,
      props.order.orderNo,
    );
    if (value?.status === "SIGNED") {
      feedback.value = {
        tone: "success",
        title: "签收事实已确认",
        message: "签收记录已经保存；订单完成状态仍以消息收敛后的查询结果为准。",
      };
      emit("receiptConfirmed");
    }
  } catch (cause) {
    if (!(cause instanceof FulfillmentAccessChangedError)) {
      throw cause;
    }
  }
}

watch(
  [
    () => props.access.ownerId,
    () => props.access.accessToken,
    () => props.order.orderNo,
    () => props.order.status,
  ],
  loadFulfillment,
  { immediate: true },
);
</script>

<template>
  <section class="order-journey-section fulfillment-section" aria-labelledby="fulfillment-title">
    <header class="journey-section-header">
      <p>配送</p>
      <h2 id="fulfillment-title">履约与物流</h2>
      <span>仓库、承运与签收进度会沿同一条事实链更新。</span>
    </header>

    <figure class="fulfillment-visual">
      <PjResponsiveImage
        :src="fulfillmentIllustration"
        :sources="fulfillmentImageSources"
        alt="青荷色包裹沿简洁路线经过多个物流节点"
        sizes="(max-width: 36rem) calc(100vw - 2rem), 72rem"
        :width="1672"
        :height="941"
        loading="lazy"
      />
      <figcaption>
        <strong>包裹沿已确认节点前进</strong>
        <span>未收到服务端确认时，页面不会提前展示完成。</span>
      </figcaption>
    </figure>

    <PjStatusNotice
      v-if="fulfillments.loadingOrderNo === order.orderNo && !fulfillment"
      tone="processing"
      title="正在读取配送进度"
    >
      <p>页面正在查询仓库与物流的最新记录。</p>
    </PjStatusNotice>

    <div v-else-if="fulfillment" class="fulfillment-content">
      <PjStatusNotice
        :tone="fulfillmentTone"
        :title="fulfillmentCopy?.title ?? '配送状态待确认'"
      >
        <p>{{ fulfillmentCopy?.detail }}</p>
        <p class="fulfillment-status-label">{{ fulfillmentCopy?.label }}</p>
      </PjStatusNotice>

      <dl class="fulfillment-facts">
        <div>
          <dt>配送记录</dt>
          <dd>{{ fulfillment.fulfillmentNo }}</dd>
        </div>
        <div>
          <dt>当前状态</dt>
          <dd>{{ fulfillment.status }}</dd>
        </div>
        <div v-if="fulfillment.carrier">
          <dt>承运商</dt>
          <dd>{{ fulfillment.carrier }}</dd>
        </div>
        <div v-if="fulfillment.trackingNo">
          <dt>运单号</dt>
          <dd>{{ fulfillment.trackingNo }}</dd>
        </div>
      </dl>

      <section
        v-if="shipmentPosition"
        class="shipment-position"
        aria-labelledby="shipment-position-title"
      >
        <div class="shipment-position__canvas" aria-hidden="true">
          <span class="shipment-position__marker"></span>
        </div>
        <div>
          <p class="eyebrow">最新可定位节点</p>
          <h3 id="shipment-position-title">
            {{ shipmentPosition.locationName || "承运商坐标节点" }}
          </h3>
          <p>
            {{ shipmentPosition.nodeType }} ·
            {{ formatTimestamp(shipmentPosition.occurredAt) }}
          </p>
          <p class="shipment-position__coordinates">
            {{ formatCoordinate(shipmentPosition.longitude) }},
            {{ formatCoordinate(shipmentPosition.latitude) }}
          </p>
          <small>
            关键轨迹以 Fulfillment 的 MySQL 事实为准；位置缓存丢失时仍可恢复查询。
          </small>
        </div>
      </section>
      <p
        v-else-if="fulfillments.loadingPositionOrderNo === order.orderNo"
        class="fulfillment-position-loading"
        role="status"
      >
        正在读取最新物流位置…
      </p>

      <PjActionGroup>
        <PjButton
          variant="secondary"
          :loading="fulfillments.loadingOrderNo === order.orderNo"
          @click="refreshFulfillment"
        >
          刷新配送状态
        </PjButton>
        <PjButton
          v-if="canConfirmReceipt"
          :loading="confirmations.confirmingOrderNo === order.orderNo"
          @click="confirmingReceipt = true"
        >
          确认收货
        </PjButton>
      </PjActionGroup>

      <PjStatusNotice
        v-if="confirmingReceipt && canConfirmReceipt"
        tone="warning"
        title="确认实际收货"
        class="fulfillment-confirmation"
        :aria-label="`确认订单 ${order.orderNo} 已收货`"
      >
        <p>
          只有实际收到并核对商品后再确认。该动作会由 Fulfillment 状态机记录签收事实。
        </p>
        <template #actions>
          <PjActionGroup>
            <PjButton
              :loading="confirmations.confirmingOrderNo === order.orderNo"
              @click="confirmReceipt"
            >
              确认已经收货
            </PjButton>
            <PjButton
              variant="text"
              :disabled="confirmations.confirmingOrderNo === order.orderNo"
              @click="confirmingReceipt = false"
            >
              暂不确认
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>

      <section class="fulfillment-timeline" aria-labelledby="fulfillment-history-title">
        <h3 id="fulfillment-history-title">履约时间线</h3>
        <ol>
          <li v-for="item in fulfillment.history" :key="`${item.command}-${item.createdAt}`">
            <div>
              <strong>{{ fulfillmentHistoryLabel(item) }}</strong>
              <span>{{ item.toStatus }}</span>
            </div>
            <time :datetime="item.createdAt">{{ formatTimestamp(item.createdAt) }}</time>
            <p v-if="item.reason">{{ item.reason }}</p>
          </li>
        </ol>
      </section>

      <section class="logistics-timeline" aria-labelledby="logistics-timeline-title">
        <h3 id="logistics-timeline-title">物流轨迹</h3>
        <ol v-if="fulfillment.traces.length">
          <li v-for="trace in fulfillment.traces" :key="trace.externalEventId">
            <div>
              <strong>{{ trace.description }}</strong>
              <span>{{ trace.nodeType }}</span>
            </div>
            <p v-if="trace.locationName">{{ trace.locationName }}</p>
            <time :datetime="trace.occurredAt">{{ formatTimestamp(trace.occurredAt) }}</time>
          </li>
        </ol>
        <p v-else class="fulfillment-empty">承运商尚未追加物流轨迹。</p>
      </section>
    </div>

    <PjStatusNotice
      v-else
      tone="processing"
      title="配送信息正在建立"
    >
      <p>
        订单已进入付款后流程，仓库记录可能仍在同步。暂时未查到记录不代表配送失败。
      </p>
      <template #actions>
        <PjButton variant="secondary" @click="refreshFulfillment">
          查询配送进度
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="confirmationUnknown"
      tone="unknown"
      title="确认收货结果待确认"
    >
      <p>{{ confirmations.confirmationError }}</p>
      <template #actions>
        <PjButton
          variant="secondary"
          :loading="confirmations.confirmingOrderNo === order.orderNo"
          @click="confirmReceipt"
        >
          查询并安全重试同一确认路径
        </PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="feedback"
      class="fulfillment-feedback"
      :tone="feedback.tone"
      :title="feedback.title"
    >
      <p>{{ feedback.message }}</p>
    </PjStatusNotice>
    <PjStatusNotice v-if="fulfillments.error" tone="danger" assertive>
      <p>{{ fulfillments.error }}</p>
    </PjStatusNotice>
    <PjStatusNotice v-if="fulfillments.positionError" tone="danger" assertive>
      <p>{{ fulfillments.positionError }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-if="confirmations.confirmationError && !confirmationUnknown"
      tone="danger"
      assertive
    >
      <p>{{ confirmations.confirmationError }}</p>
    </PjStatusNotice>
  </section>
</template>

<style scoped>
.fulfillment-section {
  display: grid;
  gap: var(--pj-space-5);
}

.fulfillment-visual {
  position: relative;
  width: 100%;
  min-width: 0;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--pj-border-subtle);
  background: var(--pj-surface-media);
  aspect-ratio: 16 / 5;
}

.fulfillment-visual img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
}

.fulfillment-visual figcaption {
  position: absolute;
  bottom: var(--pj-space-4);
  left: var(--pj-space-4);
  display: grid;
  max-width: min(26rem, calc(100% - 2 * var(--pj-space-4)));
  gap: var(--pj-space-1);
  padding: var(--pj-space-3) var(--pj-space-4);
  border: 1px solid var(--pj-border-subtle);
  background: var(--pj-surface-raised);
  color: var(--pj-text-primary);
}

.fulfillment-visual figcaption span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.fulfillment-content {
  display: grid;
  gap: var(--pj-space-5);
}

.fulfillment-facts dt,
.fulfillment-position-loading,
.fulfillment-empty {
  color: var(--pj-text-secondary);
}

.fulfillment-status-label,
.fulfillment-position-loading,
.fulfillment-empty {
  margin: var(--pj-space-2) 0 0;
  font-size: var(--pj-font-size-sm);
}

.fulfillment-status-label {
  color: inherit;
}

.fulfillment-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: 0;
}

.fulfillment-facts div {
  min-width: 0;
}

.fulfillment-facts dt {
  font-size: var(--pj-font-size-xs);
}

.fulfillment-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.shipment-position {
  display: grid;
  grid-template-columns: minmax(8rem, 0.34fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
  align-items: stretch;
  padding: var(--pj-space-4);
  border: 1px solid var(--pj-border-subtle);
  background: var(--pj-surface-soft);
}

.shipment-position__canvas {
  position: relative;
  min-height: 8rem;
  overflow: hidden;
  border: 1px solid var(--pj-border-subtle);
  background:
    linear-gradient(90deg, transparent 49.5%, var(--pj-border-subtle) 50%, transparent 50.5%),
    linear-gradient(transparent 49.5%, var(--pj-border-subtle) 50%, transparent 50.5%),
    var(--pj-surface-default);
  background-size: 2rem 2rem;
}

.shipment-position__marker {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 1rem;
  height: 1rem;
  border: 0.2rem solid var(--pj-surface-default);
  background: var(--pj-brand-primary);
  border-radius: 50%;
  box-shadow: 0 0 0 0.15rem var(--pj-brand-primary);
  transform: translate(-50%, -50%);
}

.shipment-position h3,
.shipment-position p {
  margin: 0;
}

.shipment-position > div:last-child {
  display: grid;
  align-content: center;
  gap: var(--pj-space-2);
}

.shipment-position__coordinates,
.shipment-position small {
  color: var(--pj-text-secondary);
}

.shipment-position__coordinates {
  font-variant-numeric: tabular-nums;
}

.fulfillment-confirmation {
  scroll-margin-top: var(--pj-space-7);
}

.fulfillment-timeline,
.logistics-timeline {
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-timeline h3,
.logistics-timeline h3 {
  margin: 0 0 var(--pj-space-4);
  font-size: var(--pj-font-size-md);
}

.fulfillment-timeline ol,
.logistics-timeline ol {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fulfillment-timeline li,
.logistics-timeline li {
  position: relative;
  display: grid;
  gap: var(--pj-space-2);
  padding: 0 0 var(--pj-space-5) var(--pj-space-5);
  border-left: 1px solid var(--pj-border-subtle);
}

.fulfillment-timeline li::before,
.logistics-timeline li::before {
  position: absolute;
  top: 0.35rem;
  left: -0.28rem;
  width: 0.5rem;
  height: 0.5rem;
  background: var(--pj-brand-primary);
  border-radius: 50%;
  content: "";
}

.fulfillment-timeline li:last-child,
.logistics-timeline li:last-child {
  padding-bottom: 0;
}

.fulfillment-timeline li > div,
.logistics-timeline li > div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.fulfillment-timeline span,
.logistics-timeline span,
.fulfillment-timeline time,
.logistics-timeline time,
.fulfillment-timeline li > p,
.logistics-timeline li > p {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.fulfillment-timeline li > p,
.logistics-timeline li > p {
  margin: 0;
}

@media (max-width: 36rem) {
  .fulfillment-visual {
    aspect-ratio: 16 / 8;
  }

  .fulfillment-visual img {
    object-position: 58% center;
  }

  .shipment-position {
    grid-template-columns: 1fr;
  }

  .fulfillment-facts {
    grid-template-columns: 1fr;
  }
}
</style>
