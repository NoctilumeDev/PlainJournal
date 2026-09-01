<script setup lang="ts">
import { computed, onMounted, watch } from "vue";

import {
  type FulfillmentCommandPhase,
  useAdminFulfillmentStore,
} from "../entities/admin-fulfillment";
import { useStaffSessionStore } from "../stores/session";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

const session = useStaffSessionStore();
const fulfillment = useAdminFulfillmentStore();
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

const orderStatuses = [
  "CREATED",
  "PICKING",
  "PACKED",
  "SHIPPED",
  "IN_TRANSIT",
  "DELIVERING",
  "SIGNED",
  "EXCEPTION",
];
const returnStatuses = [
  "WAIT_SHIPMENT",
  "RETURNING",
  "RECEIVED",
  "INSPECTED",
];

function noticeTone(
  phase: FulfillmentCommandPhase,
): "neutral" | "processing" | "unknown" | "success" | "danger" {
  switch (phase) {
    case "processing":
      return "processing";
    case "unknown":
      return "unknown";
    case "accepted":
      return "success";
    case "rejected":
      return "danger";
    default:
      return "neutral";
  }
}

function noticeTitle(phase: FulfillmentCommandPhase): string {
  switch (phase) {
    case "processing":
      return "命令正在确认";
    case "unknown":
      return "命令结果未知";
    case "accepted":
      return "权威事实已确认";
    case "rejected":
      return "命令已被明确拒绝";
    default:
      return "履约命令";
  }
}

function loadFacts() {
  return fulfillment.loadFacts(accessContext.value);
}

watch(accessContext, (context) => {
  fulfillment.synchronizeAccess(context);
});

onMounted(() => {
  fulfillment.synchronizeAccess(accessContext.value);
  void loadFacts();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="fulfillment-page">
    <header class="fulfillment-hero">
      <div>
        <p class="eyebrow">Fulfillment 所有者域</p>
        <h1>履约与退货</h1>
        <p>
          正向发货、物流轨迹与逆向退货共享一套事实语言。
          页面只推进状态机允许的下一步，不从网络错误推断成功。
        </p>
      </div>
      <span class="status-label">ADMIN / WAREHOUSE</span>
    </header>

    <PjStatusNotice tone="neutral" title="履约边界">
      <p>
        MySQL 保存物流与退货事实；Redis GEO 仅是可重建投影。
        结果未知时冻结原业务号、命令身份与载荷，先查权威事实或使用原命令重试。
      </p>
    </PjStatusNotice>

    <div class="fulfillment-toolbar">
      <PjField
        v-slot="{ describedBy }"
        label="履约状态"
        for-id="fulfillment-status"
      >
        <select
          id="fulfillment-status"
          v-model="fulfillment.fulfillmentStatus"
          class="pj-control"
          :aria-describedby="describedBy"
          @change="loadFacts"
        >
          <option value="">全部</option>
          <option v-for="value in orderStatuses" :key="value" :value="value">
            {{ value }}
          </option>
        </select>
      </PjField>
      <PjField
        v-slot="{ describedBy }"
        label="退货状态"
        for-id="return-status"
      >
        <select
          id="return-status"
          v-model="fulfillment.returnStatus"
          class="pj-control"
          :aria-describedby="describedBy"
          @change="loadFacts"
        >
          <option value="">全部</option>
          <option v-for="value in returnStatuses" :key="value" :value="value">
            {{ value }}
          </option>
        </select>
      </PjField>
      <PjButton
        variant="text"
        :loading="fulfillment.loading"
        @click="loadFacts"
      >
        刷新两类事实
      </PjButton>
    </div>

    <PjStatusNotice
      v-if="fulfillment.loadError"
      tone="danger"
      title="履约工作区读取未完成"
      assertive
    >
      <p>{{ fulfillment.loadError }}</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="fulfillment.commandPhase !== 'idle'"
      class="fulfillment-command-notice"
      :tone="noticeTone(fulfillment.commandPhase)"
      :title="noticeTitle(fulfillment.commandPhase)"
      :assertive="fulfillment.commandPhase === 'rejected'"
    >
      <p>{{ fulfillment.commandMessage }}</p>
      <p v-if="fulfillment.pendingReferenceNo">
        待确认业务号：<code>{{ fulfillment.pendingReferenceNo }}</code>
        · {{ fulfillment.pendingCommandLabel }}
      </p>
      <template #actions>
        <PjActionGroup :stack-on-compact="true">
          <PjButton
            v-if="fulfillment.commandPhase === 'unknown'"
            variant="text"
            :loading="fulfillment.submitting"
            @click="fulfillment.readPendingAuthority(accessContext)"
          >
            读取权威事实
          </PjButton>
          <PjButton
            v-if="fulfillment.commandPhase === 'unknown'"
            variant="text"
            :loading="fulfillment.submitting"
            @click="fulfillment.retryPending(accessContext)"
          >
            使用原命令重试
          </PjButton>
          <PjButton
            v-if="['accepted', 'rejected'].includes(fulfillment.commandPhase)"
            variant="text"
            @click="fulfillment.resetCommandNotice"
          >
            收起结果
          </PjButton>
        </PjActionGroup>
      </template>
    </PjStatusNotice>

    <section class="fulfillment-section" aria-labelledby="geo-title">
      <header class="fulfillment-section__header">
        <div>
          <p class="eyebrow">MySQL 空间事实 · Redis GEO 投影</p>
          <h2 id="geo-title">附近物流位置</h2>
        </div>
        <span>缓存丢失不改变轨迹</span>
      </header>
      <PjSurface tone="soft" padding="large">
        <form
          class="fulfillment-form fulfillment-form--four"
          @submit.prevent="fulfillment.searchNearby(accessContext)"
        >
          <PjField v-slot="{ describedBy }" label="经度" for-id="geo-longitude" required>
            <input
              id="geo-longitude"
              v-model.trim="fulfillment.geoQuery.longitude"
              class="pj-control"
              required
              type="number"
              min="-180"
              max="180"
              step="0.000001"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField v-slot="{ describedBy }" label="纬度" for-id="geo-latitude" required>
            <input
              id="geo-latitude"
              v-model.trim="fulfillment.geoQuery.latitude"
              class="pj-control"
              required
              type="number"
              min="-90"
              max="90"
              step="0.000001"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField v-slot="{ describedBy }" label="半径（米）" for-id="geo-radius" required>
            <input
              id="geo-radius"
              v-model.trim="fulfillment.geoQuery.radiusMeters"
              class="pj-control"
              required
              type="number"
              min="1"
              max="2000000"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField v-slot="{ describedBy }" label="结果上限" for-id="geo-limit" required>
            <input
              id="geo-limit"
              v-model.trim="fulfillment.geoQuery.limit"
              class="pj-control"
              required
              type="number"
              min="1"
              max="200"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjActionGroup class="fulfillment-form__wide">
            <PjButton type="submit" :loading="fulfillment.geoBusy">
              查询范围内位置
            </PjButton>
            <PjButton
              variant="text"
              :disabled="fulfillment.geoBusy"
              @click="fulfillment.rebuildGeoCache(accessContext)"
            >
              从 MySQL 重建 Redis GEO
            </PjButton>
          </PjActionGroup>
        </form>
        <PjStatusNotice
          v-if="fulfillment.geoMessage"
          class="fulfillment-inline-notice"
          tone="success"
          title="空间事实已返回"
        >
          <p>{{ fulfillment.geoMessage }}</p>
        </PjStatusNotice>
        <PjStatusNotice
          v-if="fulfillment.geoError"
          class="fulfillment-inline-notice"
          tone="danger"
          title="空间操作未完成"
          assertive
        >
          <p>{{ fulfillment.geoError }}</p>
        </PjStatusNotice>
      </PjSurface>

      <div
        v-if="fulfillment.nearbyPositions.length"
        class="fulfillment-position-grid"
      >
        <PjSurface
          v-for="position in fulfillment.nearbyPositions"
          :key="position.fulfillmentNo"
          as="article"
          tone="plain"
          padding="medium"
          class="fulfillment-position"
        >
          <header>
            <div>
              <p class="eyebrow">{{ position.fulfillmentNo }}</p>
              <h3>{{ position.locationName || "未命名坐标节点" }}</h3>
            </div>
            <span>{{ Number(position.distanceMeters).toFixed(0) }} m</span>
          </header>
          <dl class="fulfillment-facts">
            <div><dt>订单</dt><dd>{{ position.orderNo }}</dd></div>
            <div><dt>状态</dt><dd>{{ position.status }} / {{ position.nodeType }}</dd></div>
            <div><dt>经度</dt><dd>{{ position.longitude }}</dd></div>
            <div><dt>纬度</dt><dd>{{ position.latitude }}</dd></div>
          </dl>
        </PjSurface>
      </div>
    </section>

    <section class="fulfillment-section" aria-labelledby="forward-title">
      <header class="fulfillment-section__header">
        <div>
          <p class="eyebrow">正向履约</p>
          <h2 id="forward-title">从拣货到签收</h2>
        </div>
        <span>{{ fulfillment.fulfillments.length }} 条当前事实</span>
      </header>
      <div
        v-if="!fulfillment.loading && fulfillment.fulfillments.length === 0"
        class="fulfillment-empty"
      >
        当前筛选条件下没有履约单。
      </div>
      <div v-else class="fulfillment-list">
        <PjSurface
          v-for="item in fulfillment.fulfillments"
          :key="item.fulfillmentNo"
          as="article"
          tone="raised"
          padding="large"
          class="fulfillment-record"
        >
          <header class="fulfillment-record__header">
            <div>
              <p class="eyebrow">{{ item.fulfillmentNo }}</p>
              <h3>订单 {{ item.orderNo }}</h3>
            </div>
            <span class="status-label">{{ item.status }}</span>
          </header>
          <dl class="fulfillment-facts">
            <div><dt>顾客</dt><dd>{{ item.userId }}</dd></div>
            <div><dt>收件人</dt><dd>{{ item.deliveryAddress.recipientName }}</dd></div>
            <div><dt>承运商</dt><dd>{{ item.carrier || "—" }}</dd></div>
            <div><dt>运单</dt><dd>{{ item.trackingNo || "—" }}</dd></div>
          </dl>

          <PjActionGroup
            v-if="['CREATED', 'PICKING'].includes(item.status)"
            class="fulfillment-actions"
          >
            <PjButton
              v-if="item.status === 'CREATED'"
              :disabled="fulfillment.commandBlocked"
              @click="fulfillment.startPicking(accessContext, item.fulfillmentNo)"
            >
              开始拣货
            </PjButton>
            <PjButton
              v-if="item.status === 'PICKING'"
              :disabled="fulfillment.commandBlocked"
              @click="fulfillment.markPacked(accessContext, item.fulfillmentNo)"
            >
              确认打包
            </PjButton>
          </PjActionGroup>

          <form
            v-if="item.status === 'PACKED'"
            class="fulfillment-form"
            @submit.prevent="fulfillment.ship(accessContext, item.fulfillmentNo)"
          >
            <PjField
              v-slot="{ describedBy }"
              label="承运商"
              :for-id="`ship-carrier-${item.fulfillmentNo}`"
              required
            >
              <input
                :id="`ship-carrier-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.shipForm(item.fulfillmentNo).carrier"
                class="pj-control"
                required
                pattern="[A-Za-z0-9_-]+"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="运单号"
              :for-id="`ship-tracking-${item.fulfillmentNo}`"
              required
            >
              <input
                :id="`ship-tracking-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.shipForm(item.fulfillmentNo).trackingNo"
                class="pj-control"
                required
                pattern="[A-Za-z0-9._:\-]+"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton type="submit" :disabled="fulfillment.commandBlocked">
              确认发货
            </PjButton>
          </form>

          <form
            v-if="['SHIPPED', 'IN_TRANSIT', 'DELIVERING'].includes(item.status)"
            class="fulfillment-form fulfillment-form--four"
            @submit.prevent="fulfillment.addTrace(accessContext, item.fulfillmentNo)"
          >
            <PjField
              v-slot="{ describedBy }"
              label="事件 ID"
              :for-id="`trace-id-${item.fulfillmentNo}`"
              hint="结果未知时必须原样复用。"
              required
            >
              <input
                :id="`trace-id-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.traceForm(item.fulfillmentNo).externalEventId"
                class="pj-control fulfillment-command-id"
                required
                readonly
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="节点"
              :for-id="`trace-node-${item.fulfillmentNo}`"
            >
              <select
                :id="`trace-node-${item.fulfillmentNo}`"
                v-model="fulfillment.traceForm(item.fulfillmentNo).nodeType"
                class="pj-control"
                :disabled="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              >
                <option>TRANSIT</option>
                <option>DELIVERING</option>
                <option>SIGNED</option>
                <option>EXCEPTION</option>
              </select>
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="地点"
              :for-id="`trace-location-${item.fulfillmentNo}`"
            >
              <input
                :id="`trace-location-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.traceForm(item.fulfillmentNo).locationName"
                class="pj-control"
                maxlength="120"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="经度（可选）"
              :for-id="`trace-longitude-${item.fulfillmentNo}`"
            >
              <input
                :id="`trace-longitude-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.traceForm(item.fulfillmentNo).longitude"
                class="pj-control"
                type="number"
                min="-180"
                max="180"
                step="0.000001"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="纬度（可选）"
              :for-id="`trace-latitude-${item.fulfillmentNo}`"
            >
              <input
                :id="`trace-latitude-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.traceForm(item.fulfillmentNo).latitude"
                class="pj-control"
                type="number"
                min="-90"
                max="90"
                step="0.000001"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="轨迹说明"
              :for-id="`trace-description-${item.fulfillmentNo}`"
              required
              class="fulfillment-form__wide"
            >
              <input
                :id="`trace-description-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.traceForm(item.fulfillmentNo).description"
                class="pj-control"
                required
                maxlength="240"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton type="submit" :disabled="fulfillment.commandBlocked">
              追加物流轨迹
            </PjButton>
          </form>

          <form
            v-if="!['CREATED', 'PACKED', 'SIGNED', 'CANCELED', 'EXCEPTION'].includes(item.status)"
            class="fulfillment-form fulfillment-form--exception"
            @submit.prevent="fulfillment.markException(accessContext, item.fulfillmentNo)"
          >
            <PjField
              v-slot="{ describedBy }"
              label="履约异常原因"
              :for-id="`exception-reason-${item.fulfillmentNo}`"
              hint="异常会进入状态历史，不能用弹窗短文本替代审计事实。"
              required
              class="fulfillment-form__wide"
            >
              <textarea
                :id="`exception-reason-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.exceptionForm(item.fulfillmentNo).reason"
                class="pj-control"
                required
                maxlength="500"
                rows="3"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton
              type="submit"
              variant="destructive"
              :disabled="fulfillment.commandBlocked"
            >
              标记履约异常
            </PjButton>
          </form>

          <form
            v-if="item.status === 'EXCEPTION' && isAdmin"
            class="fulfillment-form"
            @submit.prevent="fulfillment.resolveException(accessContext, item.fulfillmentNo)"
          >
            <PjField
              v-slot="{ describedBy }"
              label="恢复命令 ID"
              :for-id="`resolve-id-${item.fulfillmentNo}`"
              hint="响应丢失后不得生成第二个命令 ID。"
              required
            >
              <input
                :id="`resolve-id-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.resolutionForm(item.fulfillmentNo).commandId"
                class="pj-control fulfillment-command-id"
                required
                maxlength="64"
                readonly
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField
              v-slot="{ describedBy }"
              label="管理员复核说明"
              :for-id="`resolve-reason-${item.fulfillmentNo}`"
              hint="只恢复到已审计的异常前状态。"
              required
            >
              <textarea
                :id="`resolve-reason-${item.fulfillmentNo}`"
                v-model.trim="fulfillment.resolutionForm(item.fulfillmentNo).reason"
                class="pj-control"
                required
                maxlength="500"
                rows="3"
                :readonly="fulfillment.pendingReferenceNo === item.fulfillmentNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton type="submit" :disabled="fulfillment.commandBlocked">
              恢复到异常前状态
            </PjButton>
          </form>
          <PjStatusNotice
            v-else-if="item.status === 'EXCEPTION'"
            tone="warning"
            title="需要管理员复核"
          >
            <p>WAREHOUSE 可标记异常，但只有 ADMIN 能执行异常恢复。</p>
          </PjStatusNotice>

          <details
            v-if="item.history.length || item.traces.length"
            class="fulfillment-history"
          >
            <summary>查看 {{ item.history.length }} 条状态历史与 {{ item.traces.length }} 条轨迹</summary>
            <ol>
              <li v-for="history in item.history" :key="`${history.createdAt}:${history.command}`">
                <strong>{{ history.fromStatus || "—" }} → {{ history.toStatus }}</strong>
                <span>{{ history.command }} · {{ history.operatorType }} / {{ history.operatorId }}</span>
                <small>{{ history.reason || "无补充原因" }} · {{ history.createdAt }}</small>
              </li>
            </ol>
          </details>
        </PjSurface>
      </div>
    </section>

    <section class="fulfillment-section" aria-labelledby="return-title">
      <header class="fulfillment-section__header">
        <div>
          <p class="eyebrow">逆向履约</p>
          <h2 id="return-title">退货收货与验收</h2>
        </div>
        <span>{{ fulfillment.returns.length }} 条当前事实</span>
      </header>
      <div
        v-if="!fulfillment.loading && fulfillment.returns.length === 0"
        class="fulfillment-empty"
      >
        当前筛选条件下没有退货单。
      </div>
      <div v-else class="fulfillment-list">
        <PjSurface
          v-for="item in fulfillment.returns"
          :key="item.returnReceiptNo"
          as="article"
          tone="plain"
          padding="large"
          class="fulfillment-record"
        >
          <header class="fulfillment-record__header">
            <div>
              <p class="eyebrow">{{ item.returnReceiptNo }}</p>
              <h3>售后 {{ item.afterSaleNo }}</h3>
            </div>
            <span class="status-label">{{ item.status }}</span>
          </header>
          <dl class="fulfillment-facts">
            <div><dt>订单</dt><dd>{{ item.orderNo }}</dd></div>
            <div><dt>仓库</dt><dd>{{ item.warehouseId }}</dd></div>
            <div><dt>承运商</dt><dd>{{ item.carrier || "—" }}</dd></div>
            <div><dt>运单</dt><dd>{{ item.trackingNo || "—" }}</dd></div>
          </dl>
          <PjActionGroup v-if="item.status === 'RETURNING'">
            <PjButton
              :disabled="fulfillment.commandBlocked"
              @click="fulfillment.receiveReturn(accessContext, item.returnReceiptNo)"
            >
              确认仓库收货
            </PjButton>
          </PjActionGroup>
          <form
            v-if="item.status === 'RECEIVED'"
            class="fulfillment-form"
            @submit.prevent="fulfillment.inspectReturn(accessContext, item.returnReceiptNo)"
          >
            <PjField
              v-slot="{ describedBy }"
              label="验收说明"
              :for-id="`inspect-remark-${item.returnReceiptNo}`"
              hint="验收事实会推进库存幂等回补和后续退款。"
              required
              class="fulfillment-form__wide"
            >
              <textarea
                :id="`inspect-remark-${item.returnReceiptNo}`"
                v-model.trim="fulfillment.inspectRemarks[item.returnReceiptNo]"
                class="pj-control"
                required
                maxlength="500"
                rows="3"
                :readonly="fulfillment.pendingReferenceNo === item.returnReceiptNo"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton type="submit" :disabled="fulfillment.commandBlocked">
              确认验收
            </PjButton>
          </form>
          <p class="fulfillment-boundary">
            验收完成只发布 ReturnInspected 事实；库存回补与退款继续由各自所有者域幂等推进。
          </p>
        </PjSurface>
      </div>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.fulfillment-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.fulfillment-hero,
.fulfillment-section__header,
.fulfillment-record__header,
.fulfillment-position > header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.fulfillment-hero h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.fulfillment-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.fulfillment-toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-3);
  border-block: 1px solid var(--pj-border-subtle);
}

.fulfillment-toolbar .pj-field {
  min-width: 12rem;
}

.fulfillment-command-notice,
.fulfillment-command-notice :deep(.pj-status-notice__body),
.fulfillment-command-notice :deep(.pj-status-notice__content) {
  min-width: 0;
}

.fulfillment-command-notice code {
  overflow-wrap: anywhere;
}

.fulfillment-section {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.fulfillment-section__header h2,
.fulfillment-record h3,
.fulfillment-position h3 {
  margin: 0;
}

.fulfillment-section__header > span,
.fulfillment-position > header > span,
.fulfillment-boundary {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.fulfillment-form {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: var(--pj-space-5);
  margin-top: var(--pj-space-5);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-form--four {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.fulfillment-form--exception {
  border-top-color: var(--pj-status-danger-line);
}

.fulfillment-form__wide {
  grid-column: 1 / -1;
}

.fulfillment-form textarea {
  resize: vertical;
  line-height: 1.5;
}

.fulfillment-command-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--pj-font-size-xs);
}

.fulfillment-inline-notice {
  margin-top: var(--pj-space-5);
}

.fulfillment-position-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
}

.fulfillment-position {
  min-width: 0;
  border-left: 0.2rem solid var(--pj-brand-primary);
}

.fulfillment-position > header {
  align-items: flex-start;
}

.fulfillment-list {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.fulfillment-record {
  min-width: 0;
}

.fulfillment-record__header {
  align-items: flex-start;
}

.fulfillment-record__header > div,
.fulfillment-position > header > div {
  min-width: 0;
}

.fulfillment-record h3,
.fulfillment-position h3 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-lg);
}

.fulfillment-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: var(--pj-space-5) 0 0;
}

.fulfillment-facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.fulfillment-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.fulfillment-actions {
  margin-top: var(--pj-space-5);
}

.fulfillment-history {
  margin-top: var(--pj-space-5);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.fulfillment-history summary {
  color: var(--pj-text-secondary);
  cursor: pointer;
}

.fulfillment-history ol {
  display: grid;
  gap: var(--pj-space-3);
  margin: var(--pj-space-4) 0 0;
  padding-left: var(--pj-space-5);
}

.fulfillment-history li {
  padding-left: var(--pj-space-2);
}

.fulfillment-history span,
.fulfillment-history small {
  display: block;
  overflow-wrap: anywhere;
}

.fulfillment-history small {
  color: var(--pj-text-secondary);
}

.fulfillment-empty {
  min-height: 10rem;
  display: grid;
  place-items: center;
  border-block: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
}

.fulfillment-boundary {
  margin: var(--pj-space-5) 0 0;
}

@media (max-width: 64rem) {
  .fulfillment-form--four,
  .fulfillment-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .fulfillment-hero,
  .fulfillment-section__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .fulfillment-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .fulfillment-toolbar .pj-field {
    min-width: 0;
  }

  .fulfillment-position-grid,
  .fulfillment-form,
  .fulfillment-form--four,
  .fulfillment-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .fulfillment-form__wide {
    grid-column: auto;
  }
}

@media (max-width: 32rem) {
  .fulfillment-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .fulfillment-record,
  .fulfillment-section > .pj-surface {
    padding: var(--pj-space-5);
  }

  .fulfillment-record__header,
  .fulfillment-position > header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
