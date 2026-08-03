<script setup lang="ts">
import type { RouteLocationRaw } from "vue-router";
import { RouterLink } from "vue-router";

const props = withDefaults(defineProps<{
  currentPage: number;
  totalPages: number;
  toPage: (page: number) => RouteLocationRaw;
  label?: string;
}>(), {
  label: "商品分页",
});
</script>

<template>
  <nav
    v-if="totalPages > 1"
    class="catalog-pagination"
    :aria-label="label"
  >
    <RouterLink
      v-if="currentPage > 1"
      class="catalog-pagination__link catalog-pagination__link--previous"
      :to="props.toPage(currentPage - 1)"
      rel="prev"
    >
      ← 上一页
    </RouterLink>
    <span v-else aria-hidden="true" />

    <p aria-live="polite">
      <strong>第 {{ currentPage }} 页</strong>
      <span>共 {{ totalPages }} 页</span>
    </p>

    <RouterLink
      v-if="currentPage < totalPages"
      class="catalog-pagination__link catalog-pagination__link--next"
      :to="props.toPage(currentPage + 1)"
      rel="next"
    >
      下一页 →
    </RouterLink>
    <span v-else aria-hidden="true" />
  </nav>
</template>

<style scoped>
.catalog-pagination {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: var(--pj-space-4);
  margin-top: var(--pj-space-8);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.catalog-pagination__link {
  width: fit-content;
  color: var(--pj-text-primary);
  text-underline-offset: 0.25em;
}

.catalog-pagination__link--next {
  justify-self: end;
}

.catalog-pagination p {
  display: grid;
  gap: var(--pj-space-1);
  margin: 0;
  text-align: center;
}

.catalog-pagination p span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

@media (max-width: 32rem) {
  .catalog-pagination {
    grid-template-columns: 1fr 1fr;
  }

  .catalog-pagination p {
    grid-column: 1 / -1;
    grid-row: 1;
  }
}
</style>
