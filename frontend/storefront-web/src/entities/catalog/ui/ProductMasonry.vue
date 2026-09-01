<script setup lang="ts">
import type { ProductSummary } from "@plain-journal/foundation";

import ProductCard from "./ProductCard.vue";

withDefaults(defineProps<{
  products: ProductSummary[];
  headingLevel?: 2 | 3;
}>(), {
  headingLevel: 3,
});

function mediaAspect(index: number): "landscape" | "square" {
  return index % 4 === 0 || index % 4 === 3 ? "landscape" : "square";
}
</script>

<template>
  <div class="product-masonry">
    <ProductCard
      v-for="(product, index) in products"
      :key="product.id"
      class="product-masonry__item"
      :product="product"
      :heading-level="headingLevel"
      :media-aspect="mediaAspect(index)"
    />
  </div>
</template>

<style scoped>
.product-masonry {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: clamp(2rem, 4vw, 4.5rem) clamp(1rem, 2vw, 2rem);
  align-items: start;
}

.product-masonry__item {
  grid-column: span 5;
}

.product-masonry__item:nth-child(4n + 1),
.product-masonry__item:nth-child(4n) {
  grid-column: span 7;
}

@media (max-width: 48rem) {
  .product-masonry {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .product-masonry__item,
  .product-masonry__item:nth-child(4n + 1),
  .product-masonry__item:nth-child(4n) {
    grid-column: span 1;
  }
}

@media (max-width: 32rem) {
  .product-masonry {
    gap: var(--pj-space-7) var(--pj-space-3);
  }
}
</style>
