<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  RouterLink,
  useRoute,
  useRouter,
  type RouteLocationRaw,
} from "vue-router";

import type {
  ProductSearchPage,
  ProductSummary,
} from "@plain-journal/foundation";
import {
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

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
const router = useRouter();
const input = ref("");
const products = ref<ProductSummary[]>([]);
const matchedTotal = ref(0);
const degraded = ref(false);
const source = ref<ProductSearchPage["source"] | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
let requestSequence = 0;
const query = computed(() => typeof route.query.q === "string" ? route.query.q.trim() : "");
const currentPage = computed(() => pageFromQuery(route.query.page));
const totalPages = computed(() => pageCount(matchedTotal.value, PAGE_SIZE));

function pageLocation(page: number): RouteLocationRaw {
  return {
    name: "search",
    query: queryWithPage(route.query, page),
  };
}

async function search() {
  const requestId = ++requestSequence;
  input.value = query.value;
  if (!query.value) {
    products.value = [];
    matchedTotal.value = 0;
    degraded.value = false;
    source.value = null;
    error.value = null;
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = null;
  products.value = [];
  matchedTotal.value = 0;
  degraded.value = false;
  source.value = null;
  try {
    const result = await catalogApi.searchProducts({
      q: query.value,
      page: currentPage.value,
      size: PAGE_SIZE,
    });
    if (requestId !== requestSequence) {
      return;
    }
    products.value = result.items;
    matchedTotal.value = result.matchedTotal;
    degraded.value = result.degraded;
    source.value = result.source;
  } catch (cause) {
    if (requestId !== requestSequence) {
      return;
    }
    error.value = cause instanceof Error ? cause.message : "暂时无法完成查找。";
  } finally {
    if (requestId === requestSequence) {
      loading.value = false;
    }
  }
}

function submit() {
  const normalized = input.value.trim();
  router.push({
    name: "search",
    query: normalized ? { q: normalized } : {},
  });
}

onMounted(search);
watch(() => route.fullPath, search);
</script>

<template>
  <PjPageContainer
    as="section"
    class="search-canvas"
    :class="{ 'search-canvas--has-query': query }"
  >
    <header class="search-intro">
      <p>查找</p>
      <h1>你正在寻找什么？</h1>
      <p>用名称、用途或材料开始；查询会保存在地址中，刷新后仍可恢复。</p>
    </header>
    <form class="search-form" role="search" @submit.prevent="submit">
      <PjField
        v-slot="{ describedBy }"
        label="商品名称或用途"
        for-id="site-search"
        hint="最多 80 个字符；提交新的关键词会回到第一页。"
      >
        <div class="search-form__control">
          <input
            id="site-search"
            v-model="input"
            type="search"
            maxlength="80"
            autocomplete="off"
            placeholder="例如：通勤包、书写纸品"
            :aria-describedby="describedBy"
          />
          <PjButton type="submit" variant="secondary">
            显示结果
          </PjButton>
        </div>
      </PjField>
    </form>

    <div v-if="!query" class="search-suggestions">
      <div>
        <p>没有确定名称时</p>
        <h2>从用途开始。</h2>
      </div>
      <div>
        <RouterLink :to="{ name: 'search', query: { q: '通勤' } }">通勤随行</RouterLink>
        <RouterLink :to="{ name: 'products', query: { category: 'writing' } }">
          书写纸品
        </RouterLink>
        <RouterLink :to="{ name: 'products', query: { category: 'carry' } }">
          随身用品
        </RouterLink>
      </div>
    </div>

    <section v-else class="search-results" aria-labelledby="search-results-title">
      <div class="search-results__heading">
        <div>
          <p>查找结果</p>
          <h2 id="search-results-title">“{{ query }}”</h2>
        </div>
        <p aria-live="polite">
          <strong>{{ matchedTotal }}</strong>
          <span>件匹配</span>
          <span v-if="totalPages > 1">第 {{ currentPage }} / {{ totalPages }} 页</span>
        </p>
      </div>
      <PjStatusNotice
        v-if="degraded"
        class="search-degraded"
        tone="warning"
        title="查找范围暂时收窄"
        :data-search-source="source"
      >
        <p>搜索索引暂时不可用，当前结果来自商品事实库的基础匹配。</p>
        <p>商品事实仍然有效，但排序与召回范围可能较窄。</p>
      </PjStatusNotice>
      <CatalogAsyncState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && products.length === 0"
        empty-title="没有找到匹配商品。"
        empty-message="尝试更短的关键词，或返回全部商品。"
        @retry="search"
      >
        <ProductGrid :products="products" :heading-level="2" />
        <CatalogPagination
          :current-page="currentPage"
          :total-pages="totalPages"
          :to-page="pageLocation"
          label="查找结果分页"
        />
      </CatalogAsyncState>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.search-canvas {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.search-intro {
  max-width: 52rem;
  padding-block: var(--pj-space-5) var(--pj-space-7);
}

.search-intro > p:first-child,
.search-suggestions > div:first-child > p,
.search-results__heading > div > p {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.search-intro h1 {
  margin: 0;
  max-width: 12ch;
  font-size: clamp(2.6rem, 6vw, 6rem);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: 0.98;
}

.search-intro > p:last-child {
  max-width: 38rem;
  margin: var(--pj-space-5) 0 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-md);
}

.search-canvas--has-query {
  padding-top: var(--pj-space-5);
}

.search-canvas--has-query .search-intro {
  max-width: none;
  padding-block: var(--pj-space-3) var(--pj-space-5);
}

.search-canvas--has-query .search-intro h1 {
  max-width: none;
  font-size: clamp(2.25rem, 4vw, 4rem);
}

.search-canvas--has-query .search-intro > p:last-child {
  margin-top: var(--pj-space-3);
}

.search-canvas--has-query .search-form {
  margin-bottom: var(--pj-space-6);
}

.search-form {
  margin-bottom: var(--pj-space-8);
}

.search-form__control {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-strong);
}

.search-form__control input {
  min-width: 0;
  padding: var(--pj-space-4) 0;
  border: 0;
  background: transparent;
  font-size: clamp(1.25rem, 2.5vw, 2rem);
}

.search-form__control input:focus {
  outline: none;
}

.search-form__control:focus-within {
  border-color: var(--pj-focus-ring);
  box-shadow: 0 2px var(--pj-focus-ring);
}

.search-suggestions {
  display: grid;
  grid-template-columns: minmax(12rem, 0.7fr) minmax(0, 1.3fr);
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7);
  border-top: 1px solid var(--pj-color-line);
}

.search-suggestions h2 {
  margin: 0;
  font-size: clamp(1.6rem, 3vw, 2.8rem);
  font-weight: 520;
  line-height: var(--pj-line-height-tight);
}

.search-suggestions > div:last-child {
  display: grid;
  align-content: start;
}

.search-suggestions a {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-primary);
  text-decoration: none;
}

.search-suggestions a::after {
  content: "→";
}

.search-degraded {
  margin-bottom: var(--pj-space-6);
}

.search-results__heading {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--pj-space-6);
  margin-bottom: var(--pj-space-6);
}

.search-results__heading h2 {
  margin: 0;
  font-size: clamp(2rem, 4vw, 4rem);
  font-weight: 520;
  letter-spacing: -0.045em;
  line-height: 1;
}

.search-results__heading > p {
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.search-results__heading > p strong {
  color: var(--pj-text-primary);
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
}

@media (max-width: 32rem) {
  .search-canvas--has-query .search-intro h1 {
    font-size: 2.25rem;
  }

  .search-form__control {
    grid-template-columns: 1fr;
    padding-bottom: var(--pj-space-3);
  }

  .search-form__control :deep(.pj-button) {
    width: 100%;
  }

  .search-suggestions,
  .search-results__heading {
    grid-template-columns: 1fr;
  }

  .search-results__heading > p {
    grid-template-columns: auto auto 1fr;
    justify-items: start;
    align-items: baseline;
  }
}
</style>
