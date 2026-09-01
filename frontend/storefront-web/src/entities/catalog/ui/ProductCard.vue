<script setup lang="ts">
import { RouterLink } from "vue-router";

import { formatMoney, type ProductSummary } from "@plain-journal/foundation";
import {
  PjResponsiveImage,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

withDefaults(defineProps<{
  product: ProductSummary;
  headingLevel?: 2 | 3;
  mediaAspect?: "portrait" | "square" | "landscape";
}>(), {
  headingLevel: 3,
  mediaAspect: "portrait",
});
</script>

<template>
  <article class="product-card" :class="`product-card--${mediaAspect}`">
    <RouterLink
      class="product-card__link"
      :to="{ name: 'product-detail', params: { productId: product.id } }"
    >
      <div class="product-card__media">
        <PjResponsiveImage
          v-if="product.coverUrl"
          v-bind="resolveCatalogImageDelivery(product.coverUrl)"
          :alt="product.title"
          sizes="(max-width: 48rem) 48vw, (max-width: 64rem) 31vw, 22vw"
          loading="lazy"
          decoding="async"
        />
        <div v-else class="product-card__placeholder" aria-hidden="true">
          <span>{{ product.category.name }}</span>
        </div>
      </div>
      <div class="product-card__content">
        <p class="product-card__path">
          {{ product.category.name }} / {{ product.brand.name }}
        </p>
        <component :is="`h${headingLevel}`">{{ product.title }}</component>
        <p v-if="product.subtitle" class="product-card__subtitle">
          {{ product.subtitle }}
        </p>
        <p class="product-card__price">自 {{ formatMoney(product.minimumPrice) }} 起</p>
      </div>
    </RouterLink>
  </article>
</template>

<style scoped>
.product-card {
  min-width: 0;
}

.product-card__link {
  display: grid;
  gap: var(--pj-space-4);
  color: var(--pj-text-primary);
  text-decoration: none;
}

.product-card__media {
  aspect-ratio: 4 / 5;
  overflow: hidden;
  border-radius: var(--pj-radius-md);
  background: var(--pj-surface-media);
  transition:
    background-color var(--pj-duration-normal) var(--pj-ease-standard),
    color 220ms var(--pj-ease-standard),
    border-color 220ms var(--pj-ease-standard);
}

.product-card--square .product-card__media {
  aspect-ratio: 1;
}

.product-card--landscape .product-card__media {
  aspect-ratio: 4 / 3;
}

.product-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--pj-duration-normal) var(--pj-ease-standard);
}

.product-card__link:hover img {
  transform: scale(1.015);
}

.product-card__link:focus-visible {
  outline: none;
}

.product-card__link:focus-visible .product-card__media {
  outline: 0.15rem solid var(--pj-focus-ring);
  outline-offset: 0.2rem;
}

.product-card__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background:
    linear-gradient(
      145deg,
      var(--pj-color-media-muted) 0%,
      var(--pj-color-media-highlight) 58%,
      var(--pj-color-media-shadow) 100%
    );
  color: var(--pj-color-muted);
}

.product-card__placeholder span {
  font-size: var(--pj-font-size-xs);
  letter-spacing: 0.12em;
}

.product-card__content {
  display: grid;
  gap: var(--pj-space-2);
}

.product-card__path,
.product-card__subtitle,
.product-card__price {
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.product-card__content h2,
.product-card__content h3 {
  margin: 0;
  font-size: clamp(1.05rem, 1.6vw, 1.25rem);
  font-weight: 620;
  line-height: var(--pj-line-height-tight);
}

.product-card__price {
  margin-top: var(--pj-space-1);
  color: var(--pj-text-primary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 32rem) {
  .product-card__path {
    display: none;
  }
}
</style>
