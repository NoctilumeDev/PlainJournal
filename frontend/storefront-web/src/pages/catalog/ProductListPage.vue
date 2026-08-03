<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  RouterLink,
  useRoute,
  type RouteLocationRaw,
} from "vue-router";

import type { Category, ProductSummary } from "@plain-journal/foundation";
import { PjPageContainer } from "@plain-journal/ui";

import {
  catalogApi,
  CatalogAsyncState,
  CatalogPagination,
  ProductGrid,
} from "../../entities/catalog";
import {
  pageCount,
  pageFromQuery,
  queryWithPage,
} from "../../shared/lib";

const PAGE_SIZE = 12;
const route = useRoute();
const products = ref<ProductSummary[]>([]);
const categories = ref<Category[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref<string | null>(null);

const selectedCategory = computed(() => {
  const slug = typeof route.query.category === "string" ? route.query.category : "";
  return categories.value.find((category) => category.slug === slug) ?? null;
});
const keyword = computed(() => (
  typeof route.query.q === "string" ? route.query.q.trim() : ""
));
const currentPage = computed(() => pageFromQuery(route.query.page));
const totalPages = computed(() => pageCount(total.value, PAGE_SIZE));
const pageTitle = computed(() => (
  selectedCategory.value?.name
  ?? (keyword.value ? `“${keyword.value}”的商品` : "全部商品")
));
const pageDescription = computed(() => {
  if (selectedCategory.value) {
    return `查看 ${selectedCategory.value.name} 中当前正在销售的商品。`;
  }
  if (keyword.value) {
    return "当前目录按商品事实库中的标题与说明进行基础筛选。";
  }
  return "按用途浏览当前正在销售的商品，不用促销信息替代商品事实。";
});

function pageLocation(page: number): RouteLocationRaw {
  return {
    name: "products",
    query: queryWithPage(route.query, page),
  };
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    if (categories.value.length === 0) {
      categories.value = await catalogApi.listCategories();
    }
    const page = await catalogApi.listProducts({
      page: currentPage.value,
      size: PAGE_SIZE,
      ...(selectedCategory.value ? { categoryId: selectedCategory.value.id } : {}),
      ...(keyword.value ? { keyword: keyword.value } : {}),
    });
    products.value = page.items;
    total.value = page.total;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "暂时无法读取商品。";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => route.fullPath, load);
</script>

<template>
  <PjPageContainer as="section" class="catalog-page">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/">素简记</RouterLink>
      <span aria-hidden="true">/</span>
      <span>{{ pageTitle }}</span>
    </nav>
    <header class="catalog-intro">
      <div class="catalog-intro__copy">
        <p>商品目录</p>
        <h1>{{ pageTitle }}</h1>
        <p>{{ pageDescription }}</p>
      </div>
      <p class="catalog-intro__count" aria-live="polite">
        <strong>{{ total }}</strong>
        <span>件商品</span>
        <span v-if="totalPages > 1">第 {{ currentPage }} / {{ totalPages }} 页</span>
      </p>
    </header>

    <nav class="category-rail" aria-label="商品分类">
      <RouterLink
        :to="{ name: 'products' }"
        :class="{ 'is-active': !selectedCategory }"
        :aria-current="!selectedCategory ? 'page' : undefined"
      >
        全部
      </RouterLink>
      <RouterLink
        v-for="category in categories"
        :key="category.id"
        :to="{ name: 'products', query: { category: category.slug } }"
        :class="{ 'is-active': selectedCategory?.id === category.id }"
        :aria-current="selectedCategory?.id === category.id ? 'page' : undefined"
      >
        {{ category.name }}
      </RouterLink>
    </nav>

    <CatalogAsyncState
      :loading="loading"
      :error="error"
      :empty="!loading && !error && products.length === 0"
      empty-title="没有找到符合当前条件的商品。"
      @retry="load"
    >
      <ProductGrid :products="products" :heading-level="2" />
      <CatalogPagination
        :current-page="currentPage"
        :total-pages="totalPages"
        :to-page="pageLocation"
      />
    </CatalogAsyncState>
  </PjPageContainer>
</template>

<style scoped>
.catalog-page {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.catalog-intro {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--pj-space-7);
  margin-bottom: var(--pj-space-6);
}

.catalog-intro__copy {
  max-width: var(--pj-reading-width);
}

.catalog-intro__copy > p:first-child {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.catalog-intro h1 {
  margin: 0;
  font-size: clamp(2.4rem, 5vw, 5rem);
  font-weight: 520;
  letter-spacing: -0.055em;
  line-height: 0.98;
}

.catalog-intro__copy > p:last-child {
  max-width: 38rem;
  margin: var(--pj-space-4) 0 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-md);
}

.catalog-intro__count {
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.catalog-intro__count strong {
  color: var(--pj-text-primary);
  font-size: clamp(1.8rem, 3vw, 3rem);
  font-weight: 520;
  line-height: 1;
}

.category-rail {
  display: flex;
  gap: var(--pj-space-2);
  overflow-x: auto;
  margin-bottom: var(--pj-space-7);
  padding-block: var(--pj-space-3);
  border-block: 1px solid var(--pj-color-line);
  white-space: nowrap;
  scrollbar-width: thin;
}

.category-rail a {
  padding: var(--pj-space-2) var(--pj-space-3);
  border-radius: var(--pj-radius-pill);
  color: var(--pj-text-secondary);
  text-decoration: none;
}

.category-rail a:hover {
  color: var(--pj-text-primary);
  background: var(--pj-surface-soft);
}

.category-rail a.is-active {
  color: var(--pj-action-on-primary);
  background: var(--pj-action-primary);
}

@media (max-width: 48rem) {
  .catalog-intro {
    grid-template-columns: 1fr;
    align-items: start;
    gap: var(--pj-space-4);
  }

  .catalog-intro__count {
    grid-template-columns: auto auto 1fr;
    justify-items: start;
    align-items: baseline;
  }

  .catalog-intro__count strong {
    font-size: var(--pj-font-size-xl);
  }
}
</style>
