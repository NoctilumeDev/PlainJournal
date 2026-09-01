<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";

import {
  formatMoney,
  type Category,
  type ProductSummary,
} from "@plain-journal/foundation";
import {
  PjPageContainer,
  PjResponsiveImage,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

import {
  catalogApi,
  CatalogAsyncState,
  ProductMasonry,
} from "../../entities/catalog";

const products = ref<ProductSummary[]>([]);
const categories = ref<Category[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const featuredProduct = computed(() => products.value[0] ?? null);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [productPage, categoryList] = await Promise.all([
      catalogApi.listProducts({ size: 8 }),
      catalogApi.listCategories(),
    ]);
    products.value = productPage.items;
    categories.value = categoryList;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "暂时无法读取商品。";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="home-hero-shell">
    <PjPageContainer class="home-hero">
      <div class="home-hero__copy">
        <p class="home-kicker">素简记自营选物</p>
        <h1>真正值得使用的东西，不需要促销噪音。</h1>
        <p>
          材料、尺寸、限制与使用方式，都应在购买之前被清楚理解。
          从真实需要出发，再决定是否带走。
        </p>
        <RouterLink class="primary-action" to="/products">
          浏览全部商品
        </RouterLink>
      </div>

      <RouterLink
        v-if="featuredProduct"
        class="home-featured"
        :to="{
          name: 'product-detail',
          params: { productId: featuredProduct.id },
        }"
      >
        <div class="home-featured__media">
          <PjResponsiveImage
            v-if="featuredProduct.coverUrl"
            v-bind="resolveCatalogImageDelivery(featuredProduct.coverUrl)"
            :alt="featuredProduct.title"
            sizes="(max-width: 48rem) calc(100vw - 2rem), 34vw"
            loading="eager"
            decoding="async"
            fetch-priority="high"
          />
        </div>
        <div class="home-featured__caption">
          <span>{{ featuredProduct.category.name }}</span>
          <strong>{{ featuredProduct.title }}</strong>
          <span>自 {{ formatMoney(featuredProduct.minimumPrice) }} 起</span>
        </div>
      </RouterLink>
    </PjPageContainer>
  </section>

  <PjPageContainer
    as="section"
    class="home-section home-categories"
    aria-labelledby="category-heading"
  >
    <div class="home-section__flow">
      <div class="section-heading">
        <p class="home-kicker">按用途寻找</p>
        <h2 id="category-heading">先找到要解决的问题。</h2>
      </div>
      <div class="category-links">
        <RouterLink
          v-for="category in categories"
          :key="category.id"
          :to="{ name: 'products', query: { category: category.slug } }"
        >
          <span>{{ category.name }}</span>
          <span aria-hidden="true">→</span>
        </RouterLink>
      </div>
    </div>
  </PjPageContainer>

  <PjPageContainer
    as="section"
    class="home-section product-section"
    aria-labelledby="selected-heading"
  >
    <div class="home-section__flow">
      <div class="section-heading section-heading--inline">
        <div>
          <p class="home-kicker">本期选物</p>
          <h2 id="selected-heading">先看清，再决定。</h2>
        </div>
        <RouterLink class="text-action" to="/products">查看全部商品</RouterLink>
      </div>
      <CatalogAsyncState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && products.length === 0"
        @retry="load"
      >
        <ProductMasonry :products="products" />
      </CatalogAsyncState>
    </div>
  </PjPageContainer>
</template>

<style scoped>
.home-hero-shell {
  background:
    linear-gradient(
      120deg,
      var(--pj-surface-soft) 0%,
      var(--pj-surface-soft) 56%,
      var(--pj-surface-page) 56%
    );
}

.home-hero {
  min-height: min(38rem, calc(100vh - 5rem));
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(18rem, 0.68fr);
  gap: clamp(2rem, 6vw, 7rem);
  align-items: center;
  padding-block: var(--pj-space-6);
}

.home-hero__copy {
  max-width: 43rem;
}

.home-hero__copy h1 {
  margin: 0;
  max-width: 18ch;
  font-size: clamp(2.5rem, 4.6vw, 4.5rem);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: 1.04;
}

.home-hero__copy > p:not(.home-kicker) {
  max-width: 35rem;
  margin: var(--pj-space-6) 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-lg);
}

.home-kicker {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.08em;
}

.home-featured {
  min-width: 0;
  color: inherit;
  text-decoration: none;
}

.home-featured__media {
  aspect-ratio: 1;
  overflow: hidden;
  background: var(--pj-surface-media);
}

.home-featured__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--pj-duration-normal) var(--pj-ease-standard);
}

.home-featured:hover .home-featured__media img {
  transform: scale(1.012);
}

.home-featured__caption {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--pj-space-2) var(--pj-space-4);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-strong);
}

.home-featured__caption strong {
  grid-column: 1 / -1;
  font-size: var(--pj-font-size-lg);
}

.home-featured__caption span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.home-section {
  padding-block: var(--pj-space-8);
  border-top: 1px solid var(--pj-border-subtle);
}

.home-section + .home-section {
  padding-top: var(--pj-space-7);
}

.home-section__flow {
  width: min(100%, var(--pj-layout-wide));
  margin-inline: auto;
}

.category-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--pj-border-subtle);
}

.category-links a {
  display: flex;
  justify-content: space-between;
  padding: var(--pj-space-5) 0;
  border-bottom: 1px solid var(--pj-border-subtle);
  color: inherit;
  text-decoration: none;
}

.category-links a:nth-child(odd) {
  padding-right: var(--pj-space-6);
}

.category-links a:nth-child(even) {
  padding-left: var(--pj-space-6);
  border-left: 1px solid var(--pj-border-subtle);
}

@media (max-width: 48rem) {
  .home-hero-shell {
    background: var(--pj-surface-soft);
  }

  .home-hero {
    grid-template-columns: 1fr;
    min-height: auto;
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .home-featured {
    max-width: 34rem;
  }

  .home-featured__media {
    aspect-ratio: 4 / 3;
  }

  .category-links {
    grid-template-columns: 1fr;
  }

  .category-links a:nth-child(odd),
  .category-links a:nth-child(even) {
    padding-inline: 0;
    border-left: 0;
  }
}

@media (max-width: 32rem) {
  .home-hero__copy h1 {
    font-size: clamp(2.4rem, 13vw, 3.6rem);
  }

  .home-hero__copy > p:not(.home-kicker) {
    margin-block: var(--pj-space-5);
  }

  .home-section {
    padding-block: var(--pj-space-7);
  }
}
</style>
