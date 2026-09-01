<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import { PjActionGroup, PjButton, PjField, PjStatusNotice } from "@plain-journal/ui";

import {
  type FulfillmentCommandPhase,
  useAdminFulfillmentStore,
} from "../entities/admin-fulfillment";
import { SplitWorkbench } from "../shared/ui";
import { useStaffSessionStore } from "../stores/session";

type WorkbenchMode = "forward" | "return" | "geo";

const session = useStaffSessionStore();
const fulfillment = useAdminFulfillmentStore();
const mode = ref<WorkbenchMode>("forward");
const selectedFulfillmentNo = ref<string | null>(null);
const selectedReturnReceiptNo = ref<string | null>(null);
const selectedPositionNo = ref<string | null>(null);
const roles = computed(() => session.profile?.roles ?? []);
const isAdmin = computed(() => roles.value.includes("ADMIN"));
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "WAREHOUSE"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const modeOptions: Array<{ value: WorkbenchMode; label: string; hint: string }> = [
  { value: "forward", label: "正向履约", hint: "拣货、发货与签收" },
  { value: "return", label: "逆向退货", hint: "收货、验收与回补" },
  { value: "geo", label: "附近物流", hint: "可重建位置投影" },
];
const orderStatuses = [
  ["", "全部状态"], ["CREATED", "待拣货"], ["PICKING", "拣货中"],
  ["PACKED", "待发货"], ["SHIPPED", "已发货"], ["IN_TRANSIT", "运输中"],
  ["DELIVERING", "派送中"], ["SIGNED", "已签收"], ["EXCEPTION", "履约异常"],
];
const returnStatuses = [
  ["", "全部状态"], ["WAIT_SHIPMENT", "等待寄回"], ["RETURNING", "退货途中"],
  ["RECEIVED", "仓库已收货"], ["INSPECTED", "验收完成"],
];

const selectedFulfillment = computed(() =>
  fulfillment.fulfillments.find((item) => item.fulfillmentNo === selectedFulfillmentNo.value)
  ?? fulfillment.fulfillments[0]
  ?? null,
);
const selectedReturn = computed(() =>
  fulfillment.returns.find((item) => item.returnReceiptNo === selectedReturnReceiptNo.value)
  ?? fulfillment.returns[0]
  ?? null,
);
const selectedPosition = computed(() =>
  fulfillment.nearbyPositions.find((item) => item.fulfillmentNo === selectedPositionNo.value)
  ?? fulfillment.nearbyPositions[0]
  ?? null,
);
const currentMode = computed(() => modeOptions.find((item) => item.value === mode.value)!);

function modeCount(value: WorkbenchMode): number {
  if (value === "forward") return fulfillment.fulfillments.length;
  if (value === "return") return fulfillment.returns.length;
  return fulfillment.nearbyPositions.length;
}

async function selectStatus(value: string) {
  if (mode.value === "forward") {
    fulfillment.fulfillmentStatus = value;
    selectedFulfillmentNo.value = null;
  } else {
    fulfillment.returnStatus = value;
    selectedReturnReceiptNo.value = null;
  }
  await loadFacts();
}

function noticeTone(phase: FulfillmentCommandPhase) {
  return { idle: "neutral", processing: "processing", unknown: "unknown", accepted: "success", rejected: "danger" }[phase] as "neutral" | "processing" | "unknown" | "success" | "danger";
}

function noticeTitle(phase: FulfillmentCommandPhase): string {
  return { idle: "履约命令", processing: "命令正在确认", unknown: "命令结果未知", accepted: "权威事实已确认", rejected: "命令已被明确拒绝" }[phase];
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN") : "—";
}

function loadFacts() {
  return fulfillment.loadFacts(accessContext.value);
}

watch(accessContext, (context) => fulfillment.synchronizeAccess(context));
watch(
  () => fulfillment.fulfillments.map((item) => item.fulfillmentNo),
  (values) => {
    if (values.length > 0 && !values.includes(selectedFulfillmentNo.value ?? "")) {
      selectedFulfillmentNo.value = values[0] ?? null;
    }
  },
  { immediate: true },
);
watch(
  () => fulfillment.returns.map((item) => item.returnReceiptNo),
  (values) => {
    if (values.length > 0 && !values.includes(selectedReturnReceiptNo.value ?? "")) {
      selectedReturnReceiptNo.value = values[0] ?? null;
    }
  },
  { immediate: true },
);
watch(
  () => fulfillment.nearbyPositions.map((item) => item.fulfillmentNo),
  (values) => {
    if (values.length > 0 && !values.includes(selectedPositionNo.value ?? "")) {
      selectedPositionNo.value = values[0] ?? null;
    }
  },
  { immediate: true },
);

onMounted(() => {
  fulfillment.synchronizeAccess(accessContext.value);
  void loadFacts();
});
</script>

<template>
  <SplitWorkbench label="履约与退货工作台" class="fulfillment-workbench">
    <template #rail>
      <div class="fulfillment-rail">
        <header class="fulfillment-rail__header">
          <p class="eyebrow">任务队列</p>
          <h1>履约与退货</h1>
          <p>先选择事实方向，再推进状态机允许的下一步。</p>
        </header>

        <nav class="fulfillment-mode-nav" aria-label="履约工作模式">
          <button
            v-for="option in modeOptions"
            :key="option.value"
            type="button"
            :aria-current="mode === option.value ? 'page' : undefined"
            @click="mode = option.value"
          >
            <span><strong>{{ option.label }}</strong><small>{{ option.hint }}</small></span>
            <b>{{ modeCount(option.value) }}</b>
          </button>
        </nav>

        <section v-if="mode !== 'geo'" class="fulfillment-filter" aria-labelledby="fulfillment-filter-title">
          <p id="fulfillment-filter-title" class="eyebrow">状态筛选</p>
          <div class="fulfillment-filter__options">
            <button
              v-for="option in mode === 'forward' ? orderStatuses : returnStatuses"
              :key="option[0]"
              type="button"
              :aria-pressed="mode === 'forward'
                ? fulfillment.fulfillmentStatus === option[0]
                : fulfillment.returnStatus === option[0]"
              :disabled="fulfillment.loading"
              @click="selectStatus(option[0]!)"
            >{{ option[1] }}</button>
          </div>
        </section>

        <section class="fulfillment-rail__boundary" aria-labelledby="fulfillment-boundary-title">
          <p class="eyebrow">所有者边界</p>
          <h2 id="fulfillment-boundary-title">MySQL 记录最终事实</h2>
          <p>Redis GEO 只是可重建投影。结果未知时保留原业务号、命令 ID 与载荷。</p>
        </section>

        <PjButton type="button" variant="text" :loading="fulfillment.loading" @click="loadFacts">重新读取权威事实</PjButton>
      </div>
    </template>

    <template #queue>
      <div class="fulfillment-queue">
        <header class="fulfillment-panel-header">
          <div><p class="eyebrow">{{ currentMode.hint }}</p><h2>{{ currentMode.label }}</h2></div>
          <small>{{ modeCount(mode) }} 条当前事实</small>
        </header>

        <PjStatusNotice v-if="fulfillment.loadError" tone="danger" title="履约事实读取未完成" assertive><p>{{ fulfillment.loadError }}</p></PjStatusNotice>

        <template v-if="mode === 'forward'">
          <div v-if="fulfillment.loading && !fulfillment.fulfillments.length" class="fulfillment-empty" role="status"><strong>正在读取正向履约</strong><span>筛选条件会保留。</span></div>
          <div v-else-if="!fulfillment.fulfillments.length" class="fulfillment-empty"><strong>当前没有履约单</strong><span>可切换状态或重新读取事实。</span></div>
          <ol v-else class="fulfillment-queue__list">
            <li v-for="item in fulfillment.fulfillments" :key="item.fulfillmentNo">
              <button type="button" :class="{ 'is-selected': selectedFulfillment?.fulfillmentNo === item.fulfillmentNo }" :aria-pressed="selectedFulfillment?.fulfillmentNo === item.fulfillmentNo" @click="selectedFulfillmentNo = item.fulfillmentNo">
                <span class="fulfillment-queue__identity"><code>{{ item.fulfillmentNo }}</code><strong>订单 {{ item.orderNo }}</strong></span>
                <span class="fulfillment-status" :data-tone="item.status === 'EXCEPTION' ? 'danger' : 'neutral'">{{ item.status }}</span>
                <span class="fulfillment-queue__summary">{{ item.deliveryAddress.recipientName }} · {{ item.carrier || '尚未分配承运商' }}</span>
                <time :datetime="item.updatedAt">更新于 {{ formatTime(item.updatedAt) }}</time>
              </button>
            </li>
          </ol>
        </template>

        <template v-else-if="mode === 'return'">
          <div v-if="fulfillment.loading && !fulfillment.returns.length" class="fulfillment-empty" role="status"><strong>正在读取逆向退货</strong><span>筛选条件会保留。</span></div>
          <div v-else-if="!fulfillment.returns.length" class="fulfillment-empty"><strong>当前没有退货单</strong><span>可切换状态或重新读取事实。</span></div>
          <ol v-else class="fulfillment-queue__list">
            <li v-for="item in fulfillment.returns" :key="item.returnReceiptNo">
              <button type="button" :class="{ 'is-selected': selectedReturn?.returnReceiptNo === item.returnReceiptNo }" :aria-pressed="selectedReturn?.returnReceiptNo === item.returnReceiptNo" @click="selectedReturnReceiptNo = item.returnReceiptNo">
                <span class="fulfillment-queue__identity"><code>{{ item.returnReceiptNo }}</code><strong>售后 {{ item.afterSaleNo }}</strong></span>
                <span class="fulfillment-status" data-tone="neutral">{{ item.status }}</span>
                <span class="fulfillment-queue__summary">订单 {{ item.orderNo }} · {{ item.items.length }} 个退货行</span>
                <time :datetime="item.updatedAt">更新于 {{ formatTime(item.updatedAt) }}</time>
              </button>
            </li>
          </ol>
        </template>

        <template v-else>
          <form class="fulfillment-geo-form" @submit.prevent="fulfillment.searchNearby(accessContext)">
            <div class="fulfillment-geo-form__grid">
              <PjField label="经度" for-id="geo-longitude" required><input id="geo-longitude" v-model.trim="fulfillment.geoQuery.longitude" class="pj-control" required type="number" min="-180" max="180" step="0.000001" /></PjField>
              <PjField label="纬度" for-id="geo-latitude" required><input id="geo-latitude" v-model.trim="fulfillment.geoQuery.latitude" class="pj-control" required type="number" min="-90" max="90" step="0.000001" /></PjField>
              <PjField label="半径（米）" for-id="geo-radius" required><input id="geo-radius" v-model.trim="fulfillment.geoQuery.radiusMeters" class="pj-control" required type="number" min="1" max="2000000" /></PjField>
              <PjField label="结果上限" for-id="geo-limit" required><input id="geo-limit" v-model.trim="fulfillment.geoQuery.limit" class="pj-control" required type="number" min="1" max="200" /></PjField>
            </div>
            <PjActionGroup>
              <PjButton type="submit" :loading="fulfillment.geoBusy">查询范围内位置</PjButton>
              <PjButton type="button" variant="text" :disabled="fulfillment.geoBusy" @click="fulfillment.rebuildGeoCache(accessContext)">从 MySQL 重建投影</PjButton>
            </PjActionGroup>
          </form>
          <PjStatusNotice v-if="fulfillment.geoMessage" tone="success" title="空间事实已返回"><p>{{ fulfillment.geoMessage }}</p></PjStatusNotice>
          <PjStatusNotice v-if="fulfillment.geoError" tone="danger" title="空间操作未完成" assertive><p>{{ fulfillment.geoError }}</p></PjStatusNotice>
          <div v-if="!fulfillment.nearbyPositions.length" class="fulfillment-empty"><strong>尚无附近位置结果</strong><span>输入查询范围后读取最新位置。</span></div>
          <ol v-else class="fulfillment-queue__list">
            <li v-for="item in fulfillment.nearbyPositions" :key="item.fulfillmentNo">
              <button type="button" :class="{ 'is-selected': selectedPosition?.fulfillmentNo === item.fulfillmentNo }" :aria-pressed="selectedPosition?.fulfillmentNo === item.fulfillmentNo" @click="selectedPositionNo = item.fulfillmentNo">
                <span class="fulfillment-queue__identity"><code>{{ item.fulfillmentNo }}</code><strong>{{ item.locationName || '未命名坐标节点' }}</strong></span>
                <span class="fulfillment-distance">{{ Number(item.distanceMeters).toFixed(0) }} m</span>
                <span class="fulfillment-queue__summary">{{ item.nodeType }} · 订单 {{ item.orderNo }}</span>
                <time :datetime="item.occurredAt">{{ formatTime(item.occurredAt) }}</time>
              </button>
            </li>
          </ol>
        </template>
      </div>
    </template>

    <template #detail>
      <div class="fulfillment-detail-shell">
        <PjStatusNotice v-if="fulfillment.commandPhase !== 'idle' && fulfillment.commandMessage" class="fulfillment-command-notice" :tone="noticeTone(fulfillment.commandPhase)" :title="noticeTitle(fulfillment.commandPhase)" :assertive="fulfillment.commandPhase === 'rejected'">
          <p>{{ fulfillment.commandMessage }}</p>
          <p v-if="fulfillment.pendingReferenceNo">待确认业务号：<code>{{ fulfillment.pendingReferenceNo }}</code> · {{ fulfillment.pendingCommandLabel }}</p>
          <template #actions><PjActionGroup>
            <PjButton v-if="fulfillment.commandPhase === 'unknown'" type="button" variant="text" :loading="fulfillment.submitting" @click="fulfillment.readPendingAuthority(accessContext)">读取权威事实</PjButton>
            <PjButton v-if="fulfillment.commandPhase === 'unknown'" type="button" variant="text" :loading="fulfillment.submitting" @click="fulfillment.retryPending(accessContext)">使用原命令重试</PjButton>
            <PjButton v-if="['accepted', 'rejected'].includes(fulfillment.commandPhase)" type="button" variant="text" @click="fulfillment.resetCommandNotice">收起结果</PjButton>
          </PjActionGroup></template>
        </PjStatusNotice>

        <article v-if="mode === 'forward' && selectedFulfillment" class="fulfillment-detail">
          <header class="fulfillment-detail__header"><div><p class="eyebrow">履约单 {{ selectedFulfillment.fulfillmentNo }} · 订单 {{ selectedFulfillment.orderNo }}</p><h2>订单履约事实</h2></div><span class="fulfillment-status" :data-tone="selectedFulfillment.status === 'EXCEPTION' ? 'danger' : 'neutral'">{{ selectedFulfillment.status }}</span></header>

          <section class="fulfillment-detail__section" aria-labelledby="forward-facts-title">
            <header><h3 id="forward-facts-title">履约与收件事实</h3><p>Fulfillment</p></header>
            <dl class="fulfillment-facts">
              <div><dt>顾客</dt><dd><code>{{ selectedFulfillment.userId }}</code></dd></div><div><dt>收件人</dt><dd>{{ selectedFulfillment.deliveryAddress.recipientName }}</dd></div>
              <div><dt>联系电话</dt><dd>{{ selectedFulfillment.deliveryAddress.phone }}</dd></div><div><dt>承运商</dt><dd>{{ selectedFulfillment.carrier || '尚未分配' }}</dd></div>
              <div><dt>运单号</dt><dd>{{ selectedFulfillment.trackingNo || '尚未生成' }}</dd></div><div><dt>事实版本</dt><dd>v{{ selectedFulfillment.version }}</dd></div>
              <div class="is-wide"><dt>收件地址</dt><dd>{{ selectedFulfillment.deliveryAddress.province }} {{ selectedFulfillment.deliveryAddress.city }} {{ selectedFulfillment.deliveryAddress.district }} {{ selectedFulfillment.deliveryAddress.detailAddress }}</dd></div>
            </dl>
          </section>

          <section class="fulfillment-detail__section fulfillment-next-step" aria-labelledby="forward-next-title">
            <header><h3 id="forward-next-title">当前可推进步骤</h3></header>
            <PjActionGroup v-if="['CREATED', 'PICKING'].includes(selectedFulfillment.status)">
              <PjButton v-if="selectedFulfillment.status === 'CREATED'" :disabled="fulfillment.commandBlocked" @click="fulfillment.startPicking(accessContext, selectedFulfillment.fulfillmentNo)">开始拣货</PjButton>
              <PjButton v-if="selectedFulfillment.status === 'PICKING'" :disabled="fulfillment.commandBlocked" @click="fulfillment.markPacked(accessContext, selectedFulfillment.fulfillmentNo)">确认打包</PjButton>
            </PjActionGroup>
            <form v-if="selectedFulfillment.status === 'PACKED'" class="fulfillment-form" @submit.prevent="fulfillment.ship(accessContext, selectedFulfillment.fulfillmentNo)">
              <PjField label="承运商" :for-id="`ship-carrier-${selectedFulfillment.fulfillmentNo}`" required><input :id="`ship-carrier-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.shipForm(selectedFulfillment.fulfillmentNo).carrier" class="pj-control" required pattern="[A-Za-z0-9_-]+" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjField label="运单号" :for-id="`ship-tracking-${selectedFulfillment.fulfillmentNo}`" required><input :id="`ship-tracking-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.shipForm(selectedFulfillment.fulfillmentNo).trackingNo" class="pj-control" required pattern="[A-Za-z0-9._:\-]+" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjButton type="submit" :disabled="fulfillment.commandBlocked">确认发货</PjButton>
            </form>
            <form v-if="['SHIPPED', 'IN_TRANSIT', 'DELIVERING'].includes(selectedFulfillment.status)" class="fulfillment-form" @submit.prevent="fulfillment.addTrace(accessContext, selectedFulfillment.fulfillmentNo)">
              <PjField label="事件 ID" :for-id="`trace-id-${selectedFulfillment.fulfillmentNo}`" hint="结果未知时必须原样复用。" required><input :id="`trace-id-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).externalEventId" class="pj-control fulfillment-command-id" required readonly /></PjField>
              <PjField label="节点" :for-id="`trace-node-${selectedFulfillment.fulfillmentNo}`"><select :id="`trace-node-${selectedFulfillment.fulfillmentNo}`" v-model="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).nodeType" class="pj-control" :disabled="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo"><option>TRANSIT</option><option>DELIVERING</option><option>SIGNED</option><option>EXCEPTION</option></select></PjField>
              <PjField label="地点" :for-id="`trace-location-${selectedFulfillment.fulfillmentNo}`"><input :id="`trace-location-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).locationName" class="pj-control" maxlength="120" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjField label="经度（可选）" :for-id="`trace-longitude-${selectedFulfillment.fulfillmentNo}`"><input :id="`trace-longitude-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).longitude" class="pj-control" type="number" min="-180" max="180" step="0.000001" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjField label="纬度（可选）" :for-id="`trace-latitude-${selectedFulfillment.fulfillmentNo}`"><input :id="`trace-latitude-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).latitude" class="pj-control" type="number" min="-90" max="90" step="0.000001" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjField class="is-wide" label="轨迹说明" :for-id="`trace-description-${selectedFulfillment.fulfillmentNo}`" required><input :id="`trace-description-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.traceForm(selectedFulfillment.fulfillmentNo).description" class="pj-control" required maxlength="240" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo" /></PjField>
              <PjButton type="submit" :disabled="fulfillment.commandBlocked">追加物流轨迹</PjButton>
            </form>
            <form v-if="!['CREATED', 'PACKED', 'SIGNED', 'CANCELED', 'EXCEPTION'].includes(selectedFulfillment.status)" class="fulfillment-form fulfillment-form--danger" @submit.prevent="fulfillment.markException(accessContext, selectedFulfillment.fulfillmentNo)">
              <PjField class="is-wide" label="履约异常原因" :for-id="`exception-reason-${selectedFulfillment.fulfillmentNo}`" hint="异常会进入状态历史。" required><textarea :id="`exception-reason-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.exceptionForm(selectedFulfillment.fulfillmentNo).reason" class="pj-control" required maxlength="500" rows="3" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo"></textarea></PjField>
              <PjButton type="submit" variant="destructive" :disabled="fulfillment.commandBlocked">标记履约异常</PjButton>
            </form>
            <form v-if="selectedFulfillment.status === 'EXCEPTION' && isAdmin" class="fulfillment-form" @submit.prevent="fulfillment.resolveException(accessContext, selectedFulfillment.fulfillmentNo)">
              <PjField label="恢复命令 ID" :for-id="`resolve-id-${selectedFulfillment.fulfillmentNo}`" hint="响应丢失后不得生成第二个 ID。" required><input :id="`resolve-id-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.resolutionForm(selectedFulfillment.fulfillmentNo).commandId" class="pj-control fulfillment-command-id" required maxlength="64" readonly /></PjField>
              <PjField label="管理员复核说明" :for-id="`resolve-reason-${selectedFulfillment.fulfillmentNo}`" required><textarea :id="`resolve-reason-${selectedFulfillment.fulfillmentNo}`" v-model.trim="fulfillment.resolutionForm(selectedFulfillment.fulfillmentNo).reason" class="pj-control" required maxlength="500" rows="3" :readonly="fulfillment.pendingReferenceNo === selectedFulfillment.fulfillmentNo"></textarea></PjField>
              <PjButton type="submit" :disabled="fulfillment.commandBlocked">恢复到异常前状态</PjButton>
            </form>
            <PjStatusNotice v-else-if="selectedFulfillment.status === 'EXCEPTION'" tone="warning" title="需要管理员复核"><p>WAREHOUSE 可标记异常，但只有 ADMIN 能执行异常恢复。</p></PjStatusNotice>
            <p v-if="['SIGNED', 'CANCELED'].includes(selectedFulfillment.status)" class="fulfillment-closed">当前履约已进入终态，没有待执行命令。</p>
          </section>

          <section class="fulfillment-detail__section" aria-labelledby="forward-history-title">
            <header><h3 id="forward-history-title">状态历史与物流轨迹</h3><p>{{ selectedFulfillment.history.length }} / {{ selectedFulfillment.traces.length }}</p></header>
            <ol v-if="selectedFulfillment.history.length" class="fulfillment-timeline"><li v-for="history in selectedFulfillment.history" :key="`${history.createdAt}:${history.command}`"><strong>{{ history.fromStatus || '—' }} → {{ history.toStatus }}</strong><span>{{ history.command }} · {{ history.operatorType }} / {{ history.operatorId }}</span><small>{{ history.reason || '无补充原因' }} · {{ formatTime(history.createdAt) }}</small></li></ol>
            <ol v-if="selectedFulfillment.traces.length" class="fulfillment-traces"><li v-for="trace in selectedFulfillment.traces" :key="trace.externalEventId"><strong>{{ trace.nodeType }} · {{ trace.locationName || '未命名节点' }}</strong><span>{{ trace.description }}</span><small>{{ formatTime(trace.occurredAt) }} · {{ trace.externalEventId }}</small></li></ol>
            <p v-if="!selectedFulfillment.history.length && !selectedFulfillment.traces.length" class="fulfillment-closed">尚无状态历史或物流轨迹。</p>
          </section>
        </article>

        <article v-else-if="mode === 'return' && selectedReturn" class="fulfillment-detail">
          <header class="fulfillment-detail__header"><div><p class="eyebrow">退货收货单 {{ selectedReturn.returnReceiptNo }} · 售后 {{ selectedReturn.afterSaleNo }}</p><h2>退货收货事实</h2></div><span class="fulfillment-status" data-tone="neutral">{{ selectedReturn.status }}</span></header>
          <section class="fulfillment-detail__section" aria-labelledby="return-facts-title">
            <header><h3 id="return-facts-title">退货与仓库事实</h3><p>Fulfillment</p></header>
            <dl class="fulfillment-facts">
              <div><dt>订单</dt><dd><code>{{ selectedReturn.orderNo }}</code></dd></div><div><dt>顾客</dt><dd><code>{{ selectedReturn.userId }}</code></dd></div>
              <div><dt>仓库</dt><dd><code>{{ selectedReturn.warehouseId }}</code></dd></div><div><dt>预占单</dt><dd><code>{{ selectedReturn.reservationNo }}</code></dd></div>
              <div><dt>退款金额</dt><dd>¥{{ selectedReturn.refundAmount }}</dd></div><div><dt>退货行数</dt><dd>{{ selectedReturn.items.length }}</dd></div>
              <div><dt>承运商</dt><dd>{{ selectedReturn.carrier || '尚未登记' }}</dd></div><div><dt>运单号</dt><dd>{{ selectedReturn.trackingNo || '尚未登记' }}</dd></div>
            </dl>
          </section>
          <section class="fulfillment-detail__section fulfillment-next-step" aria-labelledby="return-next-title">
            <header><h3 id="return-next-title">当前可推进步骤</h3></header>
            <PjActionGroup v-if="selectedReturn.status === 'RETURNING'"><PjButton :disabled="fulfillment.commandBlocked" @click="fulfillment.receiveReturn(accessContext, selectedReturn.returnReceiptNo)">确认仓库收货</PjButton></PjActionGroup>
            <form v-if="selectedReturn.status === 'RECEIVED'" class="fulfillment-form" @submit.prevent="fulfillment.inspectReturn(accessContext, selectedReturn.returnReceiptNo)">
              <PjField class="is-wide" label="验收说明" :for-id="`inspect-remark-${selectedReturn.returnReceiptNo}`" hint="验收事实会推进库存幂等回补和后续退款。" required><textarea :id="`inspect-remark-${selectedReturn.returnReceiptNo}`" v-model.trim="fulfillment.inspectRemarks[selectedReturn.returnReceiptNo]" class="pj-control" required maxlength="500" rows="3" :readonly="fulfillment.pendingReferenceNo === selectedReturn.returnReceiptNo"></textarea></PjField>
              <PjButton type="submit" :disabled="fulfillment.commandBlocked">确认验收</PjButton>
            </form>
            <p v-if="!['RETURNING', 'RECEIVED'].includes(selectedReturn.status)" class="fulfillment-closed">当前状态没有仓库端可执行命令。</p>
          </section>
          <section class="fulfillment-detail__section" aria-labelledby="return-lines-title">
            <header><h3 id="return-lines-title">退货商品事实</h3><p>{{ selectedReturn.items.length }} 行</p></header>
            <ol class="fulfillment-return-lines"><li v-for="line in selectedReturn.items" :key="line.lineNo"><span>行 {{ line.lineNo }} · SKU {{ line.skuId }}</span><strong>{{ line.quantity }} 件 · ¥{{ line.refundableAmount }}</strong></li></ol>
            <p class="fulfillment-closed">验收完成只发布 ReturnInspected；库存回补与退款由各自所有者域幂等推进。</p>
          </section>
        </article>

        <article v-else-if="mode === 'geo'" class="fulfillment-detail">
          <header class="fulfillment-detail__header"><div><p class="eyebrow">Redis GEO 投影</p><h2>{{ selectedPosition?.locationName || '附近物流位置' }}</h2></div><span class="fulfillment-status" data-tone="neutral">可重建</span></header>
          <section v-if="selectedPosition" class="fulfillment-detail__section" aria-labelledby="geo-facts-title">
            <header><h3 id="geo-facts-title">最新空间事实</h3><p>Fulfillment</p></header>
            <dl class="fulfillment-facts">
              <div><dt>履约单</dt><dd><code>{{ selectedPosition.fulfillmentNo }}</code></dd></div><div><dt>订单</dt><dd><code>{{ selectedPosition.orderNo }}</code></dd></div>
              <div><dt>节点</dt><dd>{{ selectedPosition.nodeType }}</dd></div><div><dt>状态</dt><dd>{{ selectedPosition.status }}</dd></div>
              <div><dt>距离</dt><dd>{{ Number(selectedPosition.distanceMeters).toFixed(0) }} m</dd></div><div><dt>发生时间</dt><dd>{{ formatTime(selectedPosition.occurredAt) }}</dd></div>
              <div><dt>经度</dt><dd>{{ selectedPosition.longitude }}</dd></div><div><dt>纬度</dt><dd>{{ selectedPosition.latitude }}</dd></div>
            </dl>
          </section>
          <section class="fulfillment-detail__section fulfillment-projection" aria-labelledby="geo-boundary-title"><header><h3 id="geo-boundary-title">投影边界</h3></header><div class="fulfillment-projection__grid"><div><span>事实源</span><strong>MySQL 物流轨迹</strong></div><div><span>查询投影</span><strong>Redis GEO</strong></div><div><span>缓存丢失</span><strong>不改变履约事实</strong></div></div></section>
        </article>

        <div v-else class="fulfillment-detail-empty"><strong>选择一条事实查看详情</strong><span>队列和详情使用同一份权威数据，不复制业务状态。</span></div>
      </div>
    </template>
  </SplitWorkbench>
</template>

<style scoped>
.fulfillment-workbench {
  --pj-focus-ring: var(--pj-brand-primary);
  --pj-color-focus: var(--pj-focus-ring);
}

.fulfillment-rail,
.fulfillment-queue,
.fulfillment-detail-shell {
  min-width: 0;
  padding: clamp(1rem, 2vw, 1.75rem);
}

.fulfillment-rail,
.fulfillment-queue,
.fulfillment-detail,
.fulfillment-detail-shell {
  display: grid;
  align-content: start;
  gap: var(--pj-space-6);
}

.fulfillment-rail__header h1,
.fulfillment-panel-header h2,
.fulfillment-detail__header h2,
.fulfillment-detail__section h3,
.fulfillment-rail__boundary h2 {
  margin: 0;
}

.fulfillment-rail__header h1 {
  font-size: clamp(1.75rem, 3vw, 2.7rem);
  font-weight: 520;
  letter-spacing: 0.035em;
  line-height: 1.15;
}

.fulfillment-rail__header > p:last-child,
.fulfillment-rail__boundary p:last-child,
.fulfillment-panel-header small,
.fulfillment-queue__summary,
.fulfillment-queue__list time,
.fulfillment-detail__section > header p,
.fulfillment-closed,
.fulfillment-detail-empty span {
  color: var(--pj-text-secondary);
}

.fulfillment-mode-nav,
.fulfillment-filter__options,
.fulfillment-queue__list {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fulfillment-mode-nav button,
.fulfillment-filter__options button,
.fulfillment-queue__list button {
  width: 100%;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.fulfillment-mode-nav button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-3);
  padding: var(--pj-space-4) var(--pj-space-3);
  border-block-start: 1px solid var(--pj-border-subtle);
}

.fulfillment-mode-nav button:last-child {
  border-block-end: 1px solid var(--pj-border-subtle);
}

.fulfillment-mode-nav button[aria-current="page"] {
  background: color-mix(in srgb, var(--pj-brand-primary) 8%, transparent);
  box-shadow: inset 0.18rem 0 var(--pj-brand-primary);
}

.fulfillment-mode-nav span,
.fulfillment-mode-nav small {
  display: block;
}

.fulfillment-mode-nav small {
  margin-top: var(--pj-space-1);
  color: var(--pj-text-secondary);
}

.fulfillment-mode-nav b {
  font-variant-numeric: tabular-nums;
}

.fulfillment-filter {
  display: grid;
  gap: var(--pj-space-3);
}

.fulfillment-filter__options {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-2);
}

.fulfillment-filter__options button {
  padding: var(--pj-space-2) var(--pj-space-3);
  border: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.fulfillment-filter__options button[aria-pressed="true"] {
  border-color: var(--pj-brand-primary);
  color: var(--pj-text-primary);
  background: color-mix(in srgb, var(--pj-brand-primary) 7%, transparent);
}

.fulfillment-rail__boundary {
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-rail__boundary h2 {
  font-size: var(--pj-font-size-md);
}

.fulfillment-panel-header,
.fulfillment-detail__header,
.fulfillment-detail__section > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.fulfillment-panel-header h2,
.fulfillment-detail__header h2 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: 0.025em;
}

.fulfillment-detail__header > div,
.fulfillment-detail__header .eyebrow,
.fulfillment-detail__header h2 {
  min-width: 0;
  overflow-wrap: anywhere;
}

.fulfillment-queue__list li + li {
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-queue__list button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--pj-space-3) var(--pj-space-4);
  padding: var(--pj-space-5) var(--pj-space-4);
}

.fulfillment-queue__list button.is-selected {
  background: color-mix(in srgb, var(--pj-brand-primary) 7%, transparent);
  box-shadow: inset 0.2rem 0 var(--pj-brand-primary);
}

.fulfillment-queue__identity,
.fulfillment-queue__identity code,
.fulfillment-queue__identity strong {
  min-width: 0;
  display: block;
}

.fulfillment-queue__identity code,
.fulfillment-queue__list time {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xs);
}

.fulfillment-queue__identity strong {
  margin-top: var(--pj-space-2);
  font-weight: 550;
}

.fulfillment-queue__summary,
.fulfillment-queue__list time {
  grid-column: 1 / -1;
}

.fulfillment-status,
.fulfillment-distance {
  align-self: start;
  padding: 0.2rem 0.45rem;
  border: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  white-space: nowrap;
}

.fulfillment-status[data-tone="danger"] {
  border-color: var(--pj-status-danger-line);
  color: var(--pj-status-danger-text);
}

.fulfillment-empty,
.fulfillment-detail-empty {
  min-height: 12rem;
  display: grid;
  place-content: center;
  gap: var(--pj-space-2);
  padding: var(--pj-space-6);
  border-block: 1px solid var(--pj-border-subtle);
  text-align: center;
}

.fulfillment-geo-form,
.fulfillment-form {
  display: grid;
  gap: var(--pj-space-5);
}

.fulfillment-geo-form {
  padding-block: var(--pj-space-5);
  border-block: 1px solid var(--pj-border-subtle);
}

.fulfillment-geo-form__grid,
.fulfillment-form {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.fulfillment-command-notice code,
.fulfillment-command-id,
.fulfillment-facts code {
  overflow-wrap: anywhere;
}

.fulfillment-detail {
  max-width: 68rem;
}

.fulfillment-detail__header {
  padding-bottom: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.fulfillment-detail__section {
  display: grid;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.fulfillment-detail__section > header h3 {
  font-size: var(--pj-font-size-md);
}

.fulfillment-detail__section > header p {
  margin: 0;
  font-size: var(--pj-font-size-sm);
}

.fulfillment-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
}

.fulfillment-facts div {
  min-width: 0;
  padding: var(--pj-space-4) var(--pj-space-4) var(--pj-space-4) 0;
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-facts div:nth-child(even) {
  padding-inline: var(--pj-space-4) 0;
  border-left: 1px solid var(--pj-border-subtle);
}

.fulfillment-facts .is-wide {
  grid-column: 1 / -1;
  padding-inline: 0;
  border-left: 0;
}

.fulfillment-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.fulfillment-facts dd {
  margin: var(--pj-space-2) 0 0;
  overflow-wrap: anywhere;
}

.fulfillment-form {
  align-items: end;
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-form .is-wide {
  grid-column: 1 / -1;
}

.fulfillment-form--danger {
  border-top-color: var(--pj-status-danger-line);
}

.fulfillment-form textarea {
  resize: vertical;
  line-height: 1.5;
}

.fulfillment-command-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--pj-font-size-xs);
}

.fulfillment-closed {
  margin: 0;
}

.fulfillment-timeline,
.fulfillment-traces,
.fulfillment-return-lines {
  display: grid;
  gap: var(--pj-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.fulfillment-timeline li,
.fulfillment-traces li,
.fulfillment-return-lines li {
  display: grid;
  gap: var(--pj-space-1);
  padding-left: var(--pj-space-4);
  border-left: 1px solid var(--pj-brand-primary);
}

.fulfillment-timeline span,
.fulfillment-timeline small,
.fulfillment-traces span,
.fulfillment-traces small {
  color: var(--pj-text-secondary);
  overflow-wrap: anywhere;
}

.fulfillment-return-lines li {
  grid-template-columns: minmax(0, 1fr) auto;
}

.fulfillment-projection__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-block: 1px solid var(--pj-border-subtle);
}

.fulfillment-projection__grid div {
  display: grid;
  gap: var(--pj-space-2);
  padding: var(--pj-space-5);
}

.fulfillment-projection__grid div + div {
  border-left: 1px solid var(--pj-border-subtle);
}

.fulfillment-projection__grid span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

@media (max-width: 72rem) {
  .fulfillment-detail { max-width: none; }
}

@media (max-width: 48rem) {
  .fulfillment-rail,
  .fulfillment-queue,
  .fulfillment-detail-shell { padding: var(--pj-space-5); }

  .fulfillment-filter__options,
  .fulfillment-geo-form__grid,
  .fulfillment-form,
  .fulfillment-projection__grid { grid-template-columns: minmax(0, 1fr); }

  .fulfillment-form .is-wide { grid-column: auto; }
  .fulfillment-projection__grid div + div { border-left: 0; border-top: 1px solid var(--pj-border-subtle); }
}

@media (max-width: 32rem) {
  .fulfillment-panel-header,
  .fulfillment-detail__header,
  .fulfillment-detail__section > header { flex-direction: column; }

  .fulfillment-facts { grid-template-columns: minmax(0, 1fr); }
  .fulfillment-facts div,
  .fulfillment-facts div:nth-child(even) { grid-column: auto; padding-inline: 0; border-left: 0; }
  .fulfillment-return-lines li { grid-template-columns: minmax(0, 1fr); }
}
</style>
