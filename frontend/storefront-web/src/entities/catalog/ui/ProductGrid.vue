<script setup lang="ts">
import type { ProductSummary } from "@plain-journal/foundation";

import ProductCard from "./ProductCard.vue";

withDefaults(defineProps<{
  products: ProductSummary[];
  headingLevel?: 2 | 3;
}>(), {
  headingLevel: 3,
});
</script>

<template>
  <div class="product-grid">
    <ProductCard
      v-for="product in products"
      :key="product.id"
      :product="product"
      :heading-level="headingLevel"
    />
  </div>
</template>

<style scoped>
.product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: clamp(2rem, 4vw, 4.5rem) clamp(1rem, 2vw, 2rem);
}

@media (max-width: 64rem) {
  .product-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 32rem) {
  .product-grid {
    gap: var(--pj-space-7) var(--pj-space-3);
  }
}
</style>
