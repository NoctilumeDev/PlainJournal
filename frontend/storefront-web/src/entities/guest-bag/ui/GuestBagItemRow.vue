<script setup lang="ts">
import { ref, watch } from "vue";
import { RouterLink } from "vue-router";

import { formatMoney, multiplyMoney } from "@plain-journal/foundation";
import {
  PjButton,
  PjResponsiveImage,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

import type { GuestBagItem } from "../model/guestBag";

const props = defineProps<{
  item: GuestBagItem;
}>();

const emit = defineEmits<{
  quantityChange: [skuId: string, quantity: number];
  remove: [skuId: string];
}>();

const quantityDraft = ref(props.item.quantity);

watch(
  () => props.item.quantity,
  (quantity) => {
    quantityDraft.value = quantity;
  },
);

function commitQuantity() {
  const quantity = Math.trunc(Number(quantityDraft.value));
  if (!Number.isFinite(quantity) || quantity < 1 || quantity > 999) {
    quantityDraft.value = props.item.quantity;
    return;
  }
  emit("quantityChange", props.item.skuId, quantity);
}
</script>

<template>
  <article class="guest-bag-row">
    <div class="guest-bag-row__media">
      <PjResponsiveImage
        v-if="item.coverUrl"
        v-bind="resolveCatalogImageDelivery(item.coverUrl)"
        :alt="item.productTitle"
        sizes="128px"
        loading="lazy"
      />
      <span v-else aria-hidden="true">{{ item.productTitle.slice(0, 1) }}</span>
    </div>
    <div class="guest-bag-row__content">
      <RouterLink
        :to="{ name: 'product-detail', params: { productId: item.productId } }"
      >
        <strong>{{ item.productTitle }}</strong>
      </RouterLink>
      <p>{{ item.skuName }}</p>
      <label>
        数量
        <input
          v-model.number="quantityDraft"
          type="number"
          min="1"
          max="999"
          inputmode="numeric"
          @change="commitQuantity"
        />
      </label>
    </div>
    <div class="guest-bag-row__aside">
      <strong>{{ formatMoney(multiplyMoney(item.unitPrice, item.quantity)) }}</strong>
      <PjButton variant="text" @click="emit('remove', item.skuId)">移出</PjButton>
    </div>
  </article>
</template>

<style scoped>
.guest-bag-row {
  display: grid;
  grid-template-columns: 8rem minmax(0, 1fr) auto;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.guest-bag-row__media {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--pj-surface-media);
  color: var(--pj-brand-primary-hover);
  font-size: var(--pj-font-size-xl);
}

.guest-bag-row__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.guest-bag-row__content p {
  margin: var(--pj-space-2) 0 var(--pj-space-4);
  color: var(--pj-text-secondary);
}

.guest-bag-row__content label {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.guest-bag-row__content input {
  width: 4rem;
  margin-left: var(--pj-space-2);
  border: 0;
  border-bottom: 1px solid var(--pj-border-strong);
  background: transparent;
  color: inherit;
}

.guest-bag-row__aside {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.guest-bag-row__aside .pj-button {
  color: var(--pj-text-secondary);
}

@media (max-width: 48rem) {
  .guest-bag-row {
    grid-template-columns: 5rem minmax(0, 1fr);
  }

  .guest-bag-row__aside {
    grid-column: 2;
    flex-direction: row;
    align-items: center;
  }
}

@media (max-width: 32rem) {
  .guest-bag-row {
    grid-template-columns: 4rem minmax(0, 1fr);
    gap: var(--pj-space-4);
  }
}
</style>
