<script setup lang="ts">
import { computed, onMounted, watch } from "vue";

import {
  type InventoryCommandPhase,
  useAdminInventoryStore,
} from "../entities/admin-inventory";
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
const inventory = useAdminInventoryStore();
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "WAREHOUSE"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

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

watch(accessContext, (context) => {
  inventory.synchronizeAccess(context);
});

onMounted(() => {
  inventory.synchronizeAccess(accessContext.value);
  void loadWarehouses();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="inventory-page">
    <header class="inventory-hero">
      <div>
        <p class="eyebrow">Inventory 所有者域</p>
        <h1>仓库与库存</h1>
        <p>
          仓库、在手、预占与版本都来自 MySQL。库存调整先冻结流水号和完整载荷，
          响应丢失后只允许查询当前事实或沿用原流水重试。
        </p>
      </div>
      <span class="status-label">ADMIN / WAREHOUSE</span>
    </header>

    <PjStatusNotice tone="neutral" title="最终裁决边界">
      <p>
        Redis 不保存最终库存。普通库存查询不公开 movementNo，因此数量或版本发生变化，
        也不能证明某条结果未知的调整已成功。
      </p>
    </PjStatusNotice>

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
            v-if="
              inventory.commandPhase === 'unknown'
              && inventory.pendingCommand?.kind === 'adjustment'
            "
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

    <section class="inventory-section" aria-labelledby="warehouses-title">
      <header class="inventory-section__header">
        <div>
          <p class="eyebrow">仓库事实</p>
          <h2 id="warehouses-title">经营仓库</h2>
        </div>
        <PjButton
          variant="text"
          :loading="inventory.loadingWarehouses"
          @click="loadWarehouses"
        >
          刷新仓库
        </PjButton>
      </header>

      <PjStatusNotice
        v-if="inventory.loadError"
        tone="danger"
        title="仓库事实读取未完成"
        assertive
      >
        <p>{{ inventory.loadError }}</p>
      </PjStatusNotice>

      <div
        v-if="!inventory.loadingWarehouses && inventory.warehouses.length === 0"
        class="inventory-empty"
      >
        当前没有可见仓库。
      </div>
      <div v-else class="inventory-warehouse-list">
        <PjSurface
          v-for="warehouse in inventory.warehouses"
          :key="warehouse.id"
          as="article"
          tone="plain"
          padding="medium"
          class="inventory-warehouse"
        >
          <header>
            <div>
              <p class="eyebrow">{{ warehouse.code }}</p>
              <h3>{{ warehouse.name }}</h3>
            </div>
            <span class="status-label">{{ warehouse.status }}</span>
          </header>
          <dl class="inventory-facts">
            <div><dt>仓库 ID</dt><dd>{{ warehouse.id }}</dd></div>
            <div><dt>版本</dt><dd>{{ warehouse.version }}</dd></div>
          </dl>
        </PjSurface>
      </div>

      <PjSurface tone="soft" padding="large">
        <form
          class="inventory-form"
          @submit.prevent="inventory.createWarehouse(accessContext)"
        >
          <PjField
            v-slot="{ describedBy }"
            label="仓库代码"
            for-id="warehouse-code"
            hint="创建接口没有额外幂等键；结果未知时只能按唯一代码和名称重读核对。"
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
          <PjField
            v-slot="{ describedBy }"
            label="仓库名称"
            for-id="warehouse-name"
            required
          >
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
          <PjButton type="submit" :disabled="inventory.commandBlocked">
            创建仓库
          </PjButton>
        </form>
      </PjSurface>
    </section>

    <section class="inventory-section" aria-labelledby="stock-title">
      <header class="inventory-section__header">
        <div>
          <p class="eyebrow">MySQL 权威库存</p>
          <h2 id="stock-title">库存位置</h2>
        </div>
        <span>在手 − 预占 = 可用</span>
      </header>
      <PjSurface tone="plain" padding="large">
        <form
          class="inventory-form"
          @submit.prevent="inventory.lookupStock(accessContext)"
        >
          <PjField
            v-slot="{ describedBy }"
            label="仓库 ID"
            for-id="stock-warehouse-id"
            required
          >
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
          <PjField
            v-slot="{ describedBy }"
            label="SKU ID"
            for-id="stock-sku-id"
            required
          >
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
          <PjButton type="submit" :loading="inventory.stockBusy">
            查询当前库存
          </PjButton>
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
      </PjSurface>
    </section>

    <section class="inventory-section" aria-labelledby="adjustment-title">
      <header class="inventory-section__header">
        <div>
          <p class="eyebrow">幂等库存流水</p>
          <h2 id="adjustment-title">调整在手库存</h2>
        </div>
        <span>同流水、同载荷才能安全重放</span>
      </header>
      <PjSurface tone="raised" padding="large">
        <form
          class="inventory-form inventory-form--adjustment"
          @submit.prevent="inventory.adjustStock(accessContext)"
        >
          <PjField
            v-slot="{ describedBy }"
            label="流水号"
            for-id="adjustment-movement-no"
            hint="Inventory 以 movementNo 和完整载荷哈希裁决幂等。"
            required
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
          <PjField
            v-slot="{ describedBy }"
            label="仓库 ID"
            for-id="adjustment-warehouse-id"
            required
          >
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
          <PjField
            v-slot="{ describedBy }"
            label="SKU ID"
            for-id="adjustment-sku-id"
            required
          >
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
          <PjButton type="submit" :disabled="inventory.commandBlocked">
            提交 Inventory 调整
          </PjButton>
        </form>
        <p class="inventory-boundary">
          查询库存只能看到当前数量和版本，不能看到原流水号。结果未知时不得根据“数字像是变了”
          判断成功，也不得生成第二个 movementNo。
        </p>
      </PjSurface>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.inventory-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.inventory-hero,
.inventory-section__header,
.inventory-warehouse > header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.inventory-hero h1,
.inventory-section__header h2,
.inventory-warehouse h3 {
  margin: 0;
}

.inventory-hero h1 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
}

.inventory-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.inventory-command-notice,
.inventory-command-notice :deep(.pj-status-notice__body),
.inventory-command-notice :deep(.pj-status-notice__content) {
  min-width: 0;
}

.inventory-command-notice code {
  overflow-wrap: anywhere;
}

.inventory-section {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.inventory-section__header > span,
.inventory-boundary {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.inventory-warehouse-list {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
}

.inventory-warehouse {
  min-width: 0;
  border-left: 0.2rem solid var(--pj-brand-primary);
}

.inventory-warehouse > header {
  align-items: flex-start;
}

.inventory-warehouse h3 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-lg);
}

.inventory-facts,
.inventory-stock-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: var(--pj-space-5) 0 0;
}

.inventory-stock-facts {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.inventory-facts div,
.inventory-stock-facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.inventory-facts dt,
.inventory-stock-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.inventory-facts dd,
.inventory-stock-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.inventory-form {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: var(--pj-space-5);
}

.inventory-form--adjustment {
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

.inventory-inline-notice,
.inventory-boundary {
  margin-top: var(--pj-space-5);
}

.inventory-empty {
  min-height: 8rem;
  display: grid;
  place-items: center;
  border-block: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
}

@media (max-width: 64rem) {
  .inventory-form--adjustment,
  .inventory-stock-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .inventory-hero,
  .inventory-section__header,
  .inventory-warehouse > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .inventory-warehouse-list,
  .inventory-form,
  .inventory-form--adjustment,
  .inventory-stock-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .inventory-form__wide {
    grid-column: auto;
  }
}

@media (max-width: 32rem) {
  .inventory-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .inventory-section > .pj-surface {
    padding: var(--pj-space-5);
  }
}
</style>
