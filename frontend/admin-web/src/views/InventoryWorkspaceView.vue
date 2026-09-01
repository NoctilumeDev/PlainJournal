<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import type { BusinessId, Warehouse } from "@plain-journal/foundation";

import {
  type InventoryCommandPhase,
  useAdminInventoryStore,
} from "../entities/admin-inventory";
import { useStaffSessionStore } from "../stores/session";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjStatusNotice,
} from "@plain-journal/ui";

import { ListWorkbench } from "../shared/ui";

const session = useStaffSessionStore();
const inventory = useAdminInventoryStore();
const selectedWarehouseId = ref<BusinessId | null>(null);
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "WAREHOUSE"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const selectedWarehouse = computed<Warehouse | null>(() =>
  inventory.warehouses.find((warehouse) => warehouse.id === selectedWarehouseId.value)
  ?? inventory.warehouses[0]
  ?? null,
);

function noticeTone(
  phase: InventoryCommandPhase,
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

function noticeTitle(phase: InventoryCommandPhase): string {
  switch (phase) {
    case "processing":
      return "库存命令正在确认";
    case "unknown":
      return "库存命令结果未知";
    case "accepted":
      return "Inventory 已确认";
    case "rejected":
      return "命令已被明确拒绝";
    default:
      return "库存命令";
  }
}

function loadWarehouses() {
  return inventory.loadWarehouses(accessContext.value);
}

function selectWarehouse(warehouse: Warehouse) {
  selectedWarehouseId.value = warehouse.id;
  inventory.lookup.warehouseId = warehouse.id;
  inventory.adjustment.warehouseId = warehouse.id;
}

watch(accessContext, (context) => {
  inventory.synchronizeAccess(context);
});

watch(
  () => inventory.warehouses.map((warehouse) => warehouse.id),
  (warehouseIds) => {
    if (warehouseIds.length > 0 && !warehouseIds.includes(selectedWarehouseId.value ?? "")) {
      selectedWarehouseId.value = warehouseIds[0] ?? null;
    }
  },
  { immediate: true },
);

watch(selectedWarehouse, (warehouse) => {
  if (!warehouse) {
    return;
  }
  if (!inventory.lookup.warehouseId) {
    inventory.lookup.warehouseId = warehouse.id;
  }
  if (!inventory.adjustment.warehouseId) {
    inventory.adjustment.warehouseId = warehouse.id;
  }
});

onMounted(() => {
  inventory.synchronizeAccess(accessContext.value);
  void loadWarehouses();
});
</script>

<template>
  <ListWorkbench label="库存清单工作区" class="inventory-workbench">
    <template #filters>
      <div class="inventory-scope-pane">
        <header class="inventory-scope-pane__header">
          <p class="eyebrow">Inventory 所有者域</p>
          <h1>仓库与库存</h1>
          <p>先选定经营仓库，再查询或调整一条 SKU 库存事实。</p>
        </header>

        <section class="inventory-scope" aria-labelledby="inventory-authority-title">
          <p class="eyebrow">最终裁决边界</p>
          <h2 id="inventory-authority-title">MySQL 保存最终事实</h2>
          <p>Redis 不保存最终库存；普通库存查询也不能证明某条结果未知的流水已经成功。</p>
        </section>

        <dl class="inventory-scope-facts">
          <div><dt>当前权限</dt><dd>ADMIN / WAREHOUSE</dd></div>
          <div><dt>可见仓库</dt><dd>{{ inventory.warehouses.length }} 个</dd></div>
          <div><dt>当前对象</dt><dd>{{ selectedWarehouse?.code ?? "尚未选择" }}</dd></div>
        </dl>

        <PjStatusNotice
          v-if="inventory.commandPhase !== 'idle'"
          class="inventory-command-notice"
          :tone="noticeTone(inventory.commandPhase)"
          :title="noticeTitle(inventory.commandPhase)"
          :assertive="inventory.commandPhase === 'rejected'"
        >
          <p>{{ inventory.commandMessage }}</p>
          <p v-if="inventory.pendingReferenceNo">
            待确认业务号：<code>{{ inventory.pendingReferenceNo }}</code>
            · {{ inventory.pendingCommandLabel }}
          </p>
          <template #actions>
            <PjActionGroup :stack-on-compact="true">
              <PjButton
                v-if="inventory.commandPhase === 'unknown'"
                variant="text"
                :loading="inventory.submitting"
                @click="inventory.readPendingAuthority(accessContext)"
              >
                读取权威事实
              </PjButton>
              <PjButton
                v-if="inventory.commandPhase === 'unknown' && inventory.pendingCommand?.kind === 'adjustment'"
                variant="text"
                :loading="inventory.submitting"
                @click="inventory.retryPending(accessContext)"
              >
                使用原流水重试
              </PjButton>
              <PjButton
                v-if="['accepted', 'rejected'].includes(inventory.commandPhase)"
                variant="text"
                @click="inventory.resetCommandNotice"
              >
                收起结果
              </PjButton>
            </PjActionGroup>
          </template>
        </PjStatusNotice>

        <PjButton variant="text" :loading="inventory.loadingWarehouses" @click="loadWarehouses">
          重新读取仓库事实
        </PjButton>
      </div>
    </template>

    <template #list>
      <div class="inventory-list-pane">
        <header class="inventory-panel-header">
          <div>
            <p class="eyebrow">仓库事实</p>
            <h2 id="warehouses-title">经营仓库</h2>
          </div>
          <small>{{ inventory.warehouses.length }} 个 · 选择后带入库存上下文</small>
        </header>

        <PjStatusNotice
          v-if="inventory.loadError"
          tone="danger"
          title="仓库事实读取未完成"
          assertive
        >
          <p>{{ inventory.loadError }}</p>
        </PjStatusNotice>

        <div v-if="!inventory.loadingWarehouses && inventory.warehouses.length === 0" class="inventory-empty">
          <strong>当前没有可见仓库</strong>
          <span>可创建仓库，或重新读取 Inventory 权威事实。</span>
        </div>
        <ol v-else class="inventory-warehouse-list" aria-labelledby="warehouses-title">
          <li v-for="warehouse in inventory.warehouses" :key="warehouse.id">
            <button
              type="button"
              :class="{ 'is-selected': selectedWarehouse?.id === warehouse.id }"
              :aria-pressed="selectedWarehouse?.id === warehouse.id"
              @click="selectWarehouse(warehouse)"
            >
              <span>
                <small>{{ warehouse.code }}</small>
                <h3>{{ warehouse.name }}</h3>
                <code>{{ warehouse.id }}</code>
              </span>
              <span class="inventory-warehouse-list__meta">
                <b>{{ warehouse.status }}</b>
                <small>版本 {{ warehouse.version }}</small>
              </span>
            </button>
          </li>
        </ol>

        <section class="inventory-create" aria-labelledby="warehouse-create-title">
          <header>
            <div>
              <p class="eyebrow">新增对象</p>
              <h2 id="warehouse-create-title">创建仓库</h2>
            </div>
            <small>唯一代码 + 名称</small>
          </header>
          <form class="inventory-form inventory-form--create" @submit.prevent="inventory.createWarehouse(accessContext)">
            <PjField
              v-slot="{ describedBy }"
              label="仓库代码"
              for-id="warehouse-code"
              hint="结果未知时只能按唯一代码和名称重读核对。"
              required
            >
              <input
                id="warehouse-code"
                v-model.trim="inventory.warehouseForm.code"
                class="pj-control"
                required
                maxlength="40"
                pattern="[A-Za-z0-9_\-]+"
                :readonly="Boolean(inventory.pendingCommand)"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjField v-slot="{ describedBy }" label="仓库名称" for-id="warehouse-name" required>
              <input
                id="warehouse-name"
                v-model.trim="inventory.warehouseForm.name"
                class="pj-control"
                required
                maxlength="100"
                :readonly="Boolean(inventory.pendingCommand)"
                :aria-describedby="describedBy"
              />
            </PjField>
            <PjButton type="submit" :disabled="inventory.commandBlocked">创建仓库</PjButton>
          </form>
        </section>
      </div>
    </template>

    <template #detail>
      <div class="inventory-detail-pane">
        <article v-if="selectedWarehouse" class="inventory-detail">
          <header class="inventory-detail__header">
            <div>
              <p class="eyebrow">{{ selectedWarehouse.code }} · 仓库 ID {{ selectedWarehouse.id }}</p>
              <h2>{{ selectedWarehouse.name }}</h2>
              <p>围绕同一仓库上下文读取当前库存，再提交一条可恢复的幂等调整。</p>
            </div>
            <span class="status-label">{{ selectedWarehouse.status }}</span>
          </header>

          <section class="inventory-detail__section" aria-labelledby="stock-title">
            <header>
              <div>
                <p class="eyebrow">MySQL 权威库存</p>
                <h2 id="stock-title">库存位置</h2>
              </div>
              <small>在手 − 预占 = 可用</small>
            </header>
            <form class="inventory-form inventory-form--lookup" @submit.prevent="inventory.lookupStock(accessContext)">
              <PjField v-slot="{ describedBy }" label="仓库 ID" for-id="stock-warehouse-id" required>
                <input
                  id="stock-warehouse-id"
                  v-model.trim="inventory.lookup.warehouseId"
                  class="pj-control"
                  required
                  inputmode="numeric"
                  pattern="[0-9]+"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjField v-slot="{ describedBy }" label="SKU ID" for-id="stock-sku-id" required>
                <input
                  id="stock-sku-id"
                  v-model.trim="inventory.lookup.skuId"
                  class="pj-control"
                  required
                  inputmode="numeric"
                  pattern="[0-9]+"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjButton type="submit" :loading="inventory.stockBusy">查询当前库存</PjButton>
            </form>
            <PjStatusNotice
              v-if="inventory.stockError"
              class="inventory-inline-notice"
              tone="danger"
              title="库存事实读取未完成"
              assertive
            >
              <p>{{ inventory.stockError }}</p>
            </PjStatusNotice>
            <dl v-if="inventory.stock" class="inventory-stock-facts">
              <div><dt>在手</dt><dd>{{ inventory.stock.onHand }}</dd></div>
              <div><dt>预占</dt><dd>{{ inventory.stock.reserved }}</dd></div>
              <div><dt>可用</dt><dd>{{ inventory.stock.available }}</dd></div>
              <div><dt>版本</dt><dd>{{ inventory.stock.version }}</dd></div>
            </dl>
            <div v-else class="inventory-stock-empty">
              输入 SKU ID 后读取当前库存；页面不会用缓存或展示状态替代权威事实。
            </div>
          </section>

          <section class="inventory-detail__section" aria-labelledby="adjustment-title">
            <header>
              <div>
                <p class="eyebrow">幂等库存流水</p>
                <h2 id="adjustment-title">调整在手库存</h2>
              </div>
              <small>同流水、同载荷才能安全重放</small>
            </header>
            <form class="inventory-form inventory-form--adjustment" @submit.prevent="inventory.adjustStock(accessContext)">
              <PjField
                v-slot="{ describedBy }"
                label="流水号"
                for-id="adjustment-movement-no"
                hint="Inventory 以 movementNo 和完整载荷哈希裁决幂等。"
                required
                class="inventory-form__wide"
              >
                <input
                  id="adjustment-movement-no"
                  v-model.trim="inventory.adjustment.movementNo"
                  class="pj-control inventory-command-id"
                  required
                  maxlength="64"
                  readonly
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjField v-slot="{ describedBy }" label="仓库 ID" for-id="adjustment-warehouse-id" required>
                <input
                  id="adjustment-warehouse-id"
                  v-model.trim="inventory.adjustment.warehouseId"
                  class="pj-control"
                  required
                  inputmode="numeric"
                  pattern="[0-9]+"
                  :readonly="Boolean(inventory.pendingCommand)"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjField v-slot="{ describedBy }" label="SKU ID" for-id="adjustment-sku-id" required>
                <input
                  id="adjustment-sku-id"
                  v-model.trim="inventory.adjustment.skuId"
                  class="pj-control"
                  required
                  inputmode="numeric"
                  pattern="[0-9]+"
                  :readonly="Boolean(inventory.pendingCommand)"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjField
                v-slot="{ describedBy }"
                label="数量变化"
                for-id="adjustment-quantity"
                hint="正数入库，负数冲减；不能为 0。"
                required
              >
                <input
                  id="adjustment-quantity"
                  v-model="inventory.adjustment.quantityDelta"
                  class="pj-control"
                  required
                  type="number"
                  min="-1000000000"
                  max="1000000000"
                  :readonly="Boolean(inventory.pendingCommand)"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjField
                v-slot="{ describedBy }"
                label="调整原因"
                for-id="adjustment-reason"
                hint="原因进入不可变库存流水。"
                required
                class="inventory-form__wide"
              >
                <textarea
                  id="adjustment-reason"
                  v-model.trim="inventory.adjustment.reason"
                  class="pj-control"
                  required
                  maxlength="240"
                  rows="3"
                  :readonly="Boolean(inventory.pendingCommand)"
                  :aria-describedby="describedBy"
                />
              </PjField>
              <PjButton type="submit" :disabled="inventory.commandBlocked">提交 Inventory 调整</PjButton>
            </form>
            <p class="inventory-boundary">
              当前数量与版本不能证明某条结果未知的流水已经成功；必须读取权威事实或沿用原 movementNo 重试。
            </p>
          </section>
        </article>

        <div v-else class="inventory-detail-empty">
          <strong>选择一个经营仓库</strong>
          <span>仓库选择只建立页面上下文，不复制或修改 Inventory 事实。</span>
        </div>
      </div>
    </template>
  </ListWorkbench>
</template>

<style scoped>
.inventory-workbench {
  --pj-focus-ring: var(--pj-brand-primary);
  --pj-color-focus: var(--pj-focus-ring);
}

.inventory-scope-pane,
.inventory-list-pane,
.inventory-detail-pane {
  min-width: 0;
  padding: clamp(1rem, 2vw, 1.75rem);
}

.inventory-scope-pane,
.inventory-list-pane,
.inventory-detail,
.inventory-detail-pane {
  display: grid;
  align-content: start;
  gap: var(--pj-space-6);
}

.inventory-scope-pane__header h1,
.inventory-scope h2,
.inventory-panel-header h2,
.inventory-create h2,
.inventory-warehouse-list h3,
.inventory-detail__header h2,
.inventory-detail__section h2 {
  margin: 0;
}

.inventory-scope-pane__header h1 {
  font-size: clamp(1.7rem, 2.8vw, 2.45rem);
  font-weight: 520;
  letter-spacing: 0.035em;
}

.inventory-scope-pane__header > p:last-child,
.inventory-scope p:last-child,
.inventory-panel-header small,
.inventory-create header small,
.inventory-detail__header p:last-child,
.inventory-detail__section header small,
.inventory-stock-empty,
.inventory-boundary,
.inventory-empty span,
.inventory-detail-empty span {
  color: var(--pj-text-secondary);
}

.inventory-scope,
.inventory-scope-facts,
.inventory-create,
.inventory-detail__section {
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.inventory-scope {
  display: grid;
  gap: var(--pj-space-3);
}

.inventory-scope h2 {
  font-size: var(--pj-font-size-md);
}

.inventory-scope-facts {
  display: grid;
  gap: var(--pj-space-3);
  margin: 0;
}

.inventory-scope-facts div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.inventory-scope-facts dt,
.inventory-stock-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.inventory-scope-facts dt {
  white-space: nowrap;
}

.inventory-scope-facts dd,
.inventory-stock-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.inventory-scope-facts dd {
  text-align: right;
}

.inventory-command-notice,
.inventory-command-notice :deep(.pj-status-notice__body),
.inventory-command-notice :deep(.pj-status-notice__content) {
  min-width: 0;
}

.inventory-command-notice code {
  overflow-wrap: anywhere;
}

.inventory-panel-header,
.inventory-create > header,
.inventory-detail__header,
.inventory-detail__section > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.inventory-panel-header h2,
.inventory-create h2,
.inventory-detail__section h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
  letter-spacing: 0.025em;
}

.inventory-warehouse-list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.inventory-warehouse-list li + li {
  border-top: 1px solid var(--pj-border-subtle);
}

.inventory-warehouse-list button {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--pj-space-4);
  padding: var(--pj-space-5) var(--pj-space-4);
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.inventory-warehouse-list button.is-selected {
  background: color-mix(in srgb, var(--pj-brand-primary) 7%, transparent);
  box-shadow: inset 0.2rem 0 var(--pj-brand-primary);
}

.inventory-warehouse-list button > span:first-child,
.inventory-warehouse-list small,
.inventory-warehouse-list code,
.inventory-warehouse-list b {
  min-width: 0;
  display: block;
}

.inventory-warehouse-list h3 {
  margin-top: var(--pj-space-1);
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-md);
  font-weight: 560;
}

.inventory-warehouse-list code {
  margin-top: var(--pj-space-2);
  overflow-wrap: anywhere;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.inventory-warehouse-list__meta {
  text-align: right;
}

.inventory-warehouse-list__meta b {
  color: var(--pj-brand-primary);
  font-size: var(--pj-font-size-xs);
}

.inventory-warehouse-list__meta small {
  margin-top: var(--pj-space-2);
  color: var(--pj-text-secondary);
}

.inventory-create {
  display: grid;
  gap: var(--pj-space-5);
}

.inventory-form {
  min-width: 0;
  display: grid;
  align-items: end;
  gap: var(--pj-space-5);
}

.inventory-form--create {
  grid-template-columns: minmax(0, 1fr);
}

.inventory-detail {
  max-width: 68rem;
}

.inventory-detail__header {
  padding-bottom: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.inventory-detail__header > div {
  min-width: 0;
}

.inventory-detail__header h2 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: 0.025em;
}

.inventory-detail__header p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
}

.inventory-detail__section {
  display: grid;
  gap: var(--pj-space-5);
  padding-bottom: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.inventory-form--lookup {
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
}

.inventory-form--adjustment {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.inventory-form__wide {
  grid-column: 1 / -1;
}

.inventory-form textarea {
  resize: vertical;
  line-height: 1.5;
}

.inventory-command-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--pj-font-size-xs);
}

.inventory-inline-notice {
  margin-top: 0;
}

.inventory-stock-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  border-block: 1px solid var(--pj-border-subtle);
}

.inventory-stock-facts div {
  min-width: 0;
  padding: var(--pj-space-4);
}

.inventory-stock-facts div + div {
  border-left: 1px solid var(--pj-border-subtle);
}

.inventory-stock-facts dd {
  margin-top: var(--pj-space-2);
  font-size: var(--pj-font-size-lg);
}

.inventory-stock-empty,
.inventory-boundary {
  padding-block: var(--pj-space-4);
  border-block: 1px solid var(--pj-border-subtle);
  font-size: var(--pj-font-size-sm);
}

.inventory-boundary {
  margin: 0;
}

.inventory-empty,
.inventory-detail-empty {
  min-height: 12rem;
  display: grid;
  place-content: center;
  gap: var(--pj-space-2);
  padding: var(--pj-space-6);
  border-block: 1px solid var(--pj-border-subtle);
  text-align: center;
}

@media (max-width: 72rem) {
  .inventory-detail {
    max-width: none;
  }
}

@media (max-width: 60rem) {
  .inventory-form--lookup,
  .inventory-form--adjustment,
  .inventory-stock-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .inventory-scope-pane,
  .inventory-list-pane,
  .inventory-detail-pane {
    padding: var(--pj-space-5);
  }
}

@media (max-width: 32rem) {
  .inventory-panel-header,
  .inventory-create > header,
  .inventory-detail__header,
  .inventory-detail__section > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .inventory-form--lookup,
  .inventory-form--adjustment,
  .inventory-stock-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .inventory-form__wide {
    grid-column: auto;
  }

  .inventory-stock-facts div + div {
    border-left: 0;
    border-top: 1px solid var(--pj-border-subtle);
  }
}
</style>
