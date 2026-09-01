<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import {
  formatMoney,
  type BusinessId,
} from "@plain-journal/foundation";

import {
  useAdminCatalogStore,
} from "../entities/admin-catalog";
import { useStaffSessionStore } from "../stores/session";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjResponsiveImage,
  PjStatusNotice,
  PjSurface,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

const session = useStaffSessionStore();
const catalog = useAdminCatalogStore();
const failedImageIds = ref<Set<BusinessId>>(new Set());
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function loadProducts() {
  return catalog.loadProducts(accessContext.value);
}

function markImageFailed(productId: BusinessId) {
  failedImageIds.value = new Set([...failedImageIds.value, productId]);
}

function refresh() {
  failedImageIds.value = new Set();
  return loadProducts();
}

watch(accessContext, (context) => {
  catalog.synchronizeAccess(context);
});

onMounted(() => {
  catalog.synchronizeAccess(accessContext.value);
  void catalog.loadWorkspace(accessContext.value);
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="catalog-page">
    <header class="catalog-hero">
      <div>
        <p class="eyebrow">Catalog 公开投影</p>
        <h1>商品目录</h1>
        <p>
          这里展示公开接口返回的 ACTIVE 商品摘要。它是浏览侧投影，不等同于 Catalog
          内部的草稿、下架商品或完整经营后台。
        </p>
      </div>
      <span class="status-label">ADMIN / OPERATOR</span>
    </header>

    <PjStatusNotice tone="neutral" title="只读边界">
      <p>
        商品、分类、品牌和图片来自公开读取契约；本页不猜测草稿或下架状态，也不提供
        管理写操作。分页总数只代表当前公开 ACTIVE 投影。
      </p>
    </PjStatusNotice>

    <PjSurface tone="soft" padding="large">
      <form class="catalog-filters" @submit.prevent="catalog.applyFilters(accessContext)">
        <PjField
          v-slot="{ describedBy }"
          label="关键词"
          for-id="catalog-keyword"
          hint="匹配商品标题或副标题。"
        >
          <input
            id="catalog-keyword"
            v-model="catalog.query.keyword"
            class="pj-control"
            maxlength="80"
            :aria-describedby="describedBy"
          />
        </PjField>
        <PjField
          v-slot="{ describedBy }"
          label="分类"
          for-id="catalog-category"
        >
          <select
            id="catalog-category"
            v-model="catalog.query.categoryId"
            class="pj-control"
            :aria-describedby="describedBy"
          >
            <option value="">全部公开分类</option>
            <option
              v-for="category in catalog.categories"
              :key="category.id"
              :value="category.id"
            >
              {{ category.name }}
            </option>
          </select>
        </PjField>
        <PjActionGroup :stack-on-compact="true">
          <PjButton
            type="submit"
            :loading="catalog.loadingProducts"
          >
            应用筛选
          </PjButton>
          <PjButton
            type="button"
            variant="text"
            :disabled="catalog.loadingProducts"
            @click="catalog.clearFilters(accessContext)"
          >
            清除筛选
          </PjButton>
          <PjButton
            type="button"
            variant="text"
            :loading="catalog.refreshing"
            @click="refresh"
          >
            重新读取
          </PjButton>
        </PjActionGroup>
      </form>
    </PjSurface>

    <PjStatusNotice
      v-if="catalog.categoriesError"
      tone="danger"
      title="分类投影读取未完成"
      assertive
    >
      <p>{{ catalog.categoriesError }}</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="catalog.productsError"
      tone="danger"
      title="商品投影读取未完成"
      assertive
    >
      <p>{{ catalog.productsError }}</p>
      <p v-if="catalog.products.length > 0">
        页面保留上一次已显示的商品事实，没有把读取失败伪装成空目录。
      </p>
    </PjStatusNotice>

    <section class="catalog-section" aria-labelledby="catalog-products-title">
      <header class="catalog-section__header">
        <div>
          <p class="eyebrow">公开 ACTIVE 商品</p>
          <h2 id="catalog-products-title">
            {{ catalog.total }} 条当前投影
          </h2>
        </div>
        <span v-if="catalog.refreshedAt">
          最近读取 {{ new Date(catalog.refreshedAt).toLocaleTimeString() }}
        </span>
      </header>

      <div
        v-if="catalog.loadingProducts && catalog.products.length === 0"
        class="catalog-state"
        role="status"
      >
        正在读取商品事实…
      </div>
      <div
        v-else-if="!catalog.loadingProducts && catalog.products.length === 0"
        class="catalog-state"
      >
        当前筛选条件下没有公开商品。
      </div>
      <div v-else class="catalog-product-list">
        <PjSurface
          v-for="product in catalog.products"
          :key="product.id"
          as="article"
          tone="plain"
          padding="medium"
          class="catalog-product"
        >
          <div
            class="catalog-product__media"
            :class="{ 'catalog-product__media--empty': !product.coverUrl || failedImageIds.has(product.id) }"
          >
            <PjResponsiveImage
              v-if="product.coverUrl && !failedImageIds.has(product.id)"
              v-bind="resolveCatalogImageDelivery(product.coverUrl)"
              :alt="`${product.title} 商品图`"
              sizes="112px"
              loading="lazy"
              @error="markImageFailed(product.id)"
            />
            <span v-else aria-hidden="true">无图片</span>
          </div>
          <div class="catalog-product__body">
            <header>
              <div>
                <p class="eyebrow">{{ product.brand.name }}</p>
                <h3>{{ product.title }}</h3>
              </div>
              <strong>{{ formatMoney(product.minimumPrice) }}</strong>
            </header>
            <p class="catalog-product__subtitle">
              {{ product.subtitle || "未填写副标题" }}
            </p>
            <dl class="catalog-product__facts">
              <div><dt>分类</dt><dd>{{ product.category.name }}</dd></div>
              <div><dt>商品 ID</dt><dd><code>{{ product.id }}</code></dd></div>
            </dl>
          </div>
        </PjSurface>
      </div>

      <footer v-if="catalog.total > 0" class="catalog-pagination">
        <span>
          显示 {{ catalog.visibleStart }}–{{ catalog.visibleEnd }}，
          第 {{ catalog.query.page }} / {{ catalog.pageCount }} 页
        </span>
        <PjActionGroup :stack-on-compact="true">
          <PjButton
            type="button"
            variant="text"
            :disabled="!catalog.hasPreviousPage || catalog.loadingProducts"
            @click="catalog.goToPage(accessContext, catalog.query.page - 1)"
          >
            上一页
          </PjButton>
          <PjButton
            type="button"
            variant="text"
            :disabled="!catalog.hasNextPage || catalog.loadingProducts"
            @click="catalog.goToPage(accessContext, catalog.query.page + 1)"
          >
            下一页
          </PjButton>
        </PjActionGroup>
      </footer>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.catalog-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.catalog-hero,
.catalog-section__header,
.catalog-pagination {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.catalog-hero h1,
.catalog-section__header h2,
.catalog-product h3 {
  margin: 0;
}

.catalog-hero h1 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

.catalog-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.catalog-filters {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(12rem, 0.65fr) auto;
  align-items: end;
  gap: var(--pj-space-5);
}

.catalog-section {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.catalog-section__header > span,
.catalog-pagination {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.catalog-product-list {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4);
}

.catalog-product {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(6rem, 7rem) minmax(0, 1fr);
  gap: var(--pj-space-5);
  border-left: 0.2rem solid var(--pj-brand-primary);
}

.catalog-product__media {
  min-width: 0;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--pj-color-surface-soft);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.catalog-product__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.catalog-product__media--empty {
  border: 1px dashed var(--pj-border-subtle);
}

.catalog-product__body {
  min-width: 0;
}

.catalog-product__body > header {
  align-items: flex-start;
}

.catalog-product__body h3 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-lg);
}

.catalog-product__body > header > strong {
  flex: 0 0 auto;
  color: var(--pj-brand-primary);
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
}

.catalog-product__subtitle {
  min-height: 3em;
  margin: var(--pj-space-3) 0 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.catalog-product__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-3);
  margin: var(--pj-space-4) 0 0;
}

.catalog-product__facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.catalog-product__facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.catalog-product__facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.catalog-state {
  min-height: 10rem;
  display: grid;
  place-items: center;
  border-block: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
}

.catalog-pagination {
  align-items: center;
}

@media (max-width: 64rem) {
  .catalog-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .catalog-filters > .pj-action-group {
    grid-column: 1 / -1;
  }
}

@media (max-width: 52rem) {
  .catalog-product-list {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 48rem) {
  .catalog-hero,
  .catalog-section__header,
  .catalog-pagination {
    align-items: flex-start;
    flex-direction: column;
  }

  .catalog-filters,
  .catalog-product__facts {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 32rem) {
  .catalog-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .catalog-section > .pj-surface {
    padding: var(--pj-space-5);
  }

  .catalog-product {
    grid-template-columns: minmax(4.5rem, 5.5rem) minmax(0, 1fr);
    gap: var(--pj-space-4);
  }

  .catalog-product__body > header {
    flex-direction: column;
    gap: var(--pj-space-2);
  }
}
</style>
