<script setup lang="ts">
import { ref, watch } from "vue";
import { RouterLink } from "vue-router";

import {
  formatMoney,
  multiplyMoney,
  type CartItem,
} from "@plain-journal/foundation";
import { PjActionGroup, PjButton } from "@plain-journal/ui";

const props = defineProps<{
  item: CartItem;
  busy: boolean;
}>();

const emit = defineEmits<{
  quantityChange: [item: CartItem, quantity: number];
  selectionChange: [item: CartItem, selected: boolean];
  remove: [item: CartItem];
}>();

const quantityDraft = ref(props.item.quantity);
const confirmingRemoval = ref(false);

watch(
  () => props.item.quantity,
  (quantity) => {
    quantityDraft.value = quantity;
  },
);

watch(
  () => props.busy,
  (busy, wasBusy) => {
    if (wasBusy && !busy) {
      quantityDraft.value = props.item.quantity;
    }
  },
);

function commitQuantity() {
  const quantity = Math.trunc(Number(quantityDraft.value));
  if (!Number.isFinite(quantity) || quantity < 1 || quantity > 999) {
    quantityDraft.value = props.item.quantity;
    return;
  }
  if (quantity !== props.item.quantity) {
    emit("quantityChange", props.item, quantity);
  }
}

function confirmRemoval() {
  confirmingRemoval.value = false;
  emit("remove", props.item);
}
</script>

<template>
  <article class="account-cart-row">
    <div class="account-cart-row__media" aria-hidden="true">
      <span>{{ item.productTitle.slice(0, 1) }}</span>
    </div>
    <div class="account-cart-row__content">
      <RouterLink
        :to="{ name: 'product-detail', params: { productId: item.productId } }"
      >
        <strong>{{ item.productTitle }}</strong>
      </RouterLink>
      <p>{{ item.skuName }}</p>
      <div class="account-cart-row__controls">
        <label>
          数量
          <input
            v-model.number="quantityDraft"
            type="number"
            min="1"
            max="999"
            inputmode="numeric"
            :disabled="busy"
            @change="commitQuantity"
          />
        </label>
        <label class="account-cart-row__selection">
          <input
            type="checkbox"
            :checked="item.selected"
            :disabled="busy"
            @change="emit(
              'selectionChange',
              item,
              ($event.target as HTMLInputElement).checked,
            )"
          />
          纳入结算
        </label>
      </div>
    </div>
    <div class="account-cart-row__aside">
      <strong>{{ formatMoney(multiplyMoney(item.unitPrice, item.quantity)) }}</strong>
      <PjButton
        v-if="!confirmingRemoval"
        variant="text"
        :disabled="busy"
        @click="confirmingRemoval = true"
      >
        移出
      </PjButton>
      <PjActionGroup
        v-else
        class="account-cart-row__confirmation"
        align="end"
        role="group"
        :aria-label="`从账户购物车移出 ${item.productTitle}`"
      >
        <PjButton variant="text" :disabled="busy" @click="confirmingRemoval = false">
          保留
        </PjButton>
        <PjButton
          class="account-cart-row__remove-confirm"
          variant="text"
          :disabled="busy"
          @click="confirmRemoval"
        >
          确认移出
        </PjButton>
      </PjActionGroup>
    </div>
  </article>
</template>

<style scoped>
.account-cart-row {
  display: grid;
  grid-template-columns: 8rem minmax(0, 1fr) auto;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.account-cart-row__media {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--pj-surface-media);
  color: var(--pj-brand-primary-hover);
  font-size: var(--pj-font-size-xl);
}

.account-cart-row__content p {
  margin: var(--pj-space-2) 0 var(--pj-space-4);
  color: var(--pj-text-secondary);
}

.account-cart-row__controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--pj-space-5);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.account-cart-row__controls input[type="number"] {
  width: 4rem;
  margin-left: var(--pj-space-2);
  border: 0;
  border-bottom: 1px solid var(--pj-border-strong);
  background: transparent;
  color: inherit;
}

.account-cart-row__selection {
  display: inline-flex;
  align-items: center;
  gap: var(--pj-space-2);
}

.account-cart-row__aside {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.account-cart-row__aside .pj-button {
  color: var(--pj-text-secondary);
}

.account-cart-row__aside .pj-button:disabled {
  opacity: 0.6;
}

.account-cart-row__confirmation {
  flex-wrap: wrap;
}

.account-cart-row__aside .account-cart-row__remove-confirm {
  color: var(--pj-status-danger-text);
}

@media (max-width: 48rem) {
  .account-cart-row {
    grid-template-columns: 5rem minmax(0, 1fr);
  }

  .account-cart-row__aside {
    grid-column: 2;
    flex-direction: row;
    align-items: center;
  }
}

@media (max-width: 32rem) {
  .account-cart-row {
    grid-template-columns: 4rem minmax(0, 1fr);
    gap: var(--pj-space-4);
  }

  .account-cart-row__aside {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
