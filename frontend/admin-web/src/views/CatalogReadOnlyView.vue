<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import {
  formatMoney,
  type BusinessId,
  type ProductSummary,
} from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjResponsiveImage,
  PjStatusNotice,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

import { useAdminCatalogStore } from "../entities/admin-catalog";
import { ListWorkbench } from "../shared/ui";
import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
const catalog = useAdminCatalogStore();
const failedImageIds = ref<Set<BusinessId>>(new Set());
const selectedProductId = ref<BusinessId | null>(null);
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const selectedProduct = computed<ProductSummary | null>(() =>
  catalog.products.find((product) => product.id === selectedProductId.value)
  ?? catalog.products[0]
  ?? null,
);
const currentCategoryLabel = computed(() =>
  catalog.categories.find((category) => category.id === catalog.query.categoryId)?.name
  ?? "全部公开分类",
);

function loadProducts() {
  return catalog.loadProducts(accessContext.value);
}

function markImageFailed(productId: BusinessId) {
  failedImageIds.value = new Set([...failedImageIds.value, productId]);
}

function imageAvailable(product: ProductSummary): boolean {
  return Boolean(product.coverUrl && !failedImageIds.value.has(product.id));
}

function selectProduct(product: ProductSummary) {
  selectedProductId.value = product.id;
}

function refresh() {
  failedImageIds.value = new Set();
  return loadProducts();
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleTimeString("zh-CN") : "尚未读取";
}

watch(accessContext, (context) => catalog.synchronizeAccess(context));
watch(
  () => catalog.products.map((product) => product.id),
  (productIds) => {
    if (productIds.length > 0 && !productIds.includes(selectedProductId.value ?? "")) {
      selectedProductId.value = productIds[0] ?? null;
    }
  },
  { immediate: true },
);

onMounted(() => {
  catalog.synchronizeAccess(accessContext.value);
  void catalog.loadWorkspace(accessContext.value);
});
</script>

<template>
  <ListWorkbench label="商品目录清单工作区" class="catalog-workbench">
    <template #filters>
      <div class="catalog-filters-pane">
        <header class="catalog-filters-pane__header">
          <p class="eyebrow">清单工作区</p>
          <h1>商品目录</h1>
          <p>先收窄公开商品范围，再比较一条浏览侧投影。</p>
        </header>

        <form class="catalog-filter-form" @submit.prevent="catalog.applyFilters(accessContext)">
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
              placeholder="输入标题或副标题"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField v-slot="{ describedBy }" label="分类" for-id="catalog-category">
            <select
              id="catalog-category"
              v-model="catalog.query.categoryId"
              class="pj-control"
              :aria-describedby="describedBy"
            >
              <option value="">全部公开分类</option>
              <option v-for="category in catalog.categories" :key="category.id" :value="category.id">
                {{ category.name }}
              </option>
            </select>
          </PjField>
          <PjActionGroup :stack-on-compact="true">
            <PjButton type="submit" :loading="catalog.loadingProducts">应用筛选</PjButton>
            <PjButton type="button" variant="text" :disabled="catalog.loadingProducts" @click="catalog.clearFilters(accessContext)">清除筛选</PjButton>
          </PjActionGroup>
        </form>

        <section class="catalog-filter-summary" aria-labelledby="catalog-current-filter">
          <p class="eyebrow">当前范围</p>
          <h2 id="catalog-current-filter">{{ currentCategoryLabel }}</h2>
          <dl>
            <div><dt>关键词</dt><dd>{{ catalog.query.keyword.trim() || "未限定" }}</dd></div>
            <div><dt>每页</dt><dd>{{ catalog.query.size }} 条</dd></div>
            <div><dt>投影状态</dt><dd>ACTIVE</dd></div>
          </dl>
        </section>

        <section class="catalog-read-boundary" aria-labelledby="catalog-read-boundary">
          <p class="eyebrow">读取边界</p>
          <h2 id="catalog-read-boundary">只读公开投影</h2>
          <p>不猜测草稿或下架商品，也不在公开摘要上伪造经营写操作。</p>
        </section>

        <PjButton type="button" variant="text" :loading="catalog.refreshing" @click="refresh">重新读取公开事实</PjButton>
      </div>
    </template>

    <template #list>
      <div class="catalog-list-pane">
        <header class="catalog-panel-header">
          <div>
            <p class="eyebrow">公开 ACTIVE 商品</p>
            <h2>商品清单</h2>
          </div>
          <small>{{ catalog.total }} 条 · 读取于 {{ formatTime(catalog.refreshedAt) }}</small>
        </header>

        <PjStatusNotice v-if="catalog.categoriesError" tone="danger" title="分类投影读取未完成" assertive>
          <p>{{ catalog.categoriesError }}</p>
        </PjStatusNotice>
        <PjStatusNotice v-if="catalog.productsError" tone="danger" title="商品投影读取未完成" assertive>
          <p>{{ catalog.productsError }}</p>
          <p v-if="catalog.products.length">保留上一次已显示的商品事实，没有把读取失败伪装成空目录。</p>
        </PjStatusNotice>

        <div v-if="catalog.loadingProducts && !catalog.products.length" class="catalog-empty" role="status">
          <strong>正在读取商品事实</strong><span>当前筛选条件会保留。</span>
        </div>
        <div v-else-if="!catalog.products.length" class="catalog-empty">
          <strong>当前范围没有公开商品</strong><span>可清除筛选或重新读取公开事实。</span>
        </div>
        <ol v-else class="catalog-list">
          <li v-for="product in catalog.products" :key="product.id">
            <button
              type="button"
              :class="{ 'is-selected': selectedProduct?.id === product.id }"
              :aria-pressed="selectedProduct?.id === product.id"
              @click="selectProduct(product)"
            >
              <span class="catalog-list__media" :class="{ 'is-empty': !imageAvailable(product) }">
                <PjResponsiveImage
                  v-if="imageAvailable(product)"
                  v-bind="resolveCatalogImageDelivery(product.coverUrl!)"
                  :alt="`${product.title} 商品图`"
                  sizes="76px"
                  loading="lazy"
                  @error="markImageFailed(product.id)"
                />
                <span v-else aria-hidden="true">无图片</span>
              </span>
              <span class="catalog-list__copy">
                <small>{{ product.brand.name }} · {{ product.category.name }}</small>
                <strong>{{ product.title }}</strong>
                <span>{{ product.subtitle || "未填写副标题" }}</span>
                <code>{{ product.id }}</code>
              </span>
              <b>{{ formatMoney(product.minimumPrice) }}</b>
            </button>
          </li>
        </ol>

        <footer v-if="catalog.total > 0" class="catalog-pagination">
          <span>显示 {{ catalog.visibleStart }}–{{ catalog.visibleEnd }} · 第 {{ catalog.query.page }} / {{ catalog.pageCount }} 页</span>
          <PjActionGroup :stack-on-compact="true">
            <PjButton type="button" variant="text" :disabled="!catalog.hasPreviousPage || catalog.loadingProducts" @click="catalog.goToPage(accessContext, catalog.query.page - 1)">上一页</PjButton>
            <PjButton type="button" variant="text" :disabled="!catalog.hasNextPage || catalog.loadingProducts" @click="catalog.goToPage(accessContext, catalog.query.page + 1)">下一页</PjButton>
          </PjActionGroup>
        </footer>
      </div>
    </template>

    <template #detail>
      <div class="catalog-detail-pane">
        <article v-if="selectedProduct" class="catalog-detail">
          <header class="catalog-detail__header">
            <div>
              <p class="eyebrow">商品 ID {{ selectedProduct.id }}</p>
              <h2>{{ selectedProduct.title }}</h2>
              <p>{{ selectedProduct.subtitle || "未填写副标题" }}</p>
            </div>
            <strong>{{ formatMoney(selectedProduct.minimumPrice) }}</strong>
          </header>

          <section class="catalog-detail__section catalog-detail__summary" aria-labelledby="catalog-product-summary">
            <div class="catalog-detail__media" :class="{ 'is-empty': !imageAvailable(selectedProduct) }">
              <PjResponsiveImage
                v-if="imageAvailable(selectedProduct)"
                v-bind="resolveCatalogImageDelivery(selectedProduct.coverUrl!)"
                :alt="`${selectedProduct.title} 商品图`"
                sizes="(max-width: 768px) 100vw, 240px"
                @error="markImageFailed(selectedProduct.id)"
              />
              <span v-else aria-hidden="true">当前投影没有可用图片</span>
            </div>
            <div>
              <header><h3 id="catalog-product-summary">公开投影事实</h3><p>Catalog</p></header>
              <dl class="catalog-facts">
                <div><dt>品牌</dt><dd>{{ selectedProduct.brand.name }}</dd></div>
                <div><dt>品牌 ID</dt><dd><code>{{ selectedProduct.brand.id }}</code></dd></div>
                <div><dt>分类</dt><dd>{{ selectedProduct.category.name }}</dd></div>
                <div><dt>分类 ID</dt><dd><code>{{ selectedProduct.category.id }}</code></dd></div>
                <div><dt>最低价格</dt><dd>{{ formatMoney(selectedProduct.minimumPrice) }}</dd></div>
                <div><dt>公开状态</dt><dd>ACTIVE</dd></div>
              </dl>
            </div>
          </section>

          <section class="catalog-detail__section" aria-labelledby="catalog-contract-title">
            <header><h3 id="catalog-contract-title">当前页面能确认什么</h3></header>
            <div class="catalog-contract-grid">
              <div><span>已确认</span><strong>公开浏览摘要</strong><p>标题、副标题、品牌、分类、最低价格与商品图。</p></div>
              <div><span>没有暴露</span><strong>经营内部状态</strong><p>草稿、下架原因、完整 SKU 与内部审核流程不在此契约。</p></div>
              <div><span>安全下一步</span><strong>回到所有者后台</strong><p>需要写操作时进入 Catalog 经营接口，而不是修改公开投影。</p></div>
            </div>
          </section>
        </article>

        <div v-else class="catalog-detail-empty">
          <strong>选择一条商品投影</strong>
          <span>清单选择只改变详情上下文，不复制或修改 Catalog 事实。</span>
        </div>
      </div>
    </template>
  </ListWorkbench>
</template>

<style scoped>
.catalog-workbench {
  --pj-focus-ring: var(--pj-brand-primary);
  --pj-color-focus: var(--pj-focus-ring);
}

.catalog-filters-pane,
.catalog-list-pane,
.catalog-detail-pane {
  min-width: 0;
  padding: clamp(1rem, 2vw, 1.75rem);
}

.catalog-filters-pane,
.catalog-list-pane,
.catalog-detail,
.catalog-detail-pane {
  display: grid;
  align-content: start;
  gap: var(--pj-space-6);
}

.catalog-filters-pane__header h1,
.catalog-filter-summary h2,
.catalog-read-boundary h2,
.catalog-panel-header h2,
.catalog-detail__header h2,
.catalog-detail__section h3 {
  margin: 0;
}

.catalog-filters-pane__header h1 {
  font-size: clamp(1.7rem, 2.8vw, 2.45rem);
  font-weight: 520;
  letter-spacing: 0.035em;
}

.catalog-filters-pane__header > p:last-child,
.catalog-read-boundary p:last-child,
.catalog-panel-header small,
.catalog-empty span,
.catalog-detail__header p:last-child,
.catalog-detail__section > header p,
.catalog-contract-grid span,
.catalog-contract-grid p,
.catalog-detail-empty span {
  color: var(--pj-text-secondary);
}

.catalog-filter-form {
  display: grid;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-block: 1px solid var(--pj-border-subtle);
}

.catalog-filter-summary,
.catalog-read-boundary {
  display: grid;
  gap: var(--pj-space-3);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.catalog-filter-summary h2,
.catalog-read-boundary h2 {
  font-size: var(--pj-font-size-md);
}

.catalog-filter-summary dl {
  display: grid;
  gap: var(--pj-space-3);
  margin: 0;
}

.catalog-filter-summary dl div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.catalog-filter-summary dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.catalog-filter-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.catalog-panel-header,
.catalog-detail__header,
.catalog-detail__section > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.catalog-panel-header h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
  letter-spacing: 0.025em;
}

.catalog-detail__header h2 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: 0.025em;
}

.catalog-list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.catalog-list li + li {
  border-top: 1px solid var(--pj-border-subtle);
}

.catalog-list button {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: 4.75rem minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--pj-space-4);
  padding: var(--pj-space-5) var(--pj-space-4);
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.catalog-list button.is-selected {
  background: color-mix(in srgb, var(--pj-brand-primary) 7%, transparent);
  box-shadow: inset 0.2rem 0 var(--pj-brand-primary);
}

.catalog-list__media {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--pj-surface-soft);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.catalog-list__media :deep(.pj-responsive-image),
.catalog-detail__media :deep(.pj-responsive-image) {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.catalog-list__media :deep(.pj-responsive-image__image),
.catalog-detail__media :deep(.pj-responsive-image__image) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.catalog-list__media.is-empty,
.catalog-detail__media.is-empty {
  border: 1px dashed var(--pj-border-subtle);
}

.catalog-list__copy,
.catalog-list__copy small,
.catalog-list__copy strong,
.catalog-list__copy span,
.catalog-list__copy code {
  min-width: 0;
  display: block;
}

.catalog-list__copy small,
.catalog-list__copy span,
.catalog-list__copy code {
  color: var(--pj-text-secondary);
}

.catalog-list__copy strong {
  margin-top: var(--pj-space-1);
  font-weight: 560;
}

.catalog-list__copy span {
  margin-top: var(--pj-space-2);
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-sm);
}

.catalog-list__copy code {
  margin-top: var(--pj-space-2);
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xs);
}

.catalog-list button > b,
.catalog-detail__header > strong {
  color: var(--pj-brand-primary);
  font-weight: 600;
  white-space: nowrap;
}

.catalog-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.catalog-empty,
.catalog-detail-empty {
  min-height: 12rem;
  display: grid;
  place-content: center;
  gap: var(--pj-space-2);
  padding: var(--pj-space-6);
  border-block: 1px solid var(--pj-border-subtle);
  text-align: center;
}

.catalog-detail {
  max-width: 68rem;
}

.catalog-detail__header {
  padding-bottom: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.catalog-detail__header > div {
  min-width: 0;
}

.catalog-detail__header p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
}

.catalog-detail__header > strong {
  font-size: var(--pj-font-size-lg);
}

.catalog-detail__section {
  display: grid;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.catalog-detail__section > header h3 {
  font-size: var(--pj-font-size-md);
}

.catalog-detail__section > header p {
  margin: 0;
  font-size: var(--pj-font-size-sm);
}

.catalog-detail__summary {
  grid-template-columns: minmax(11rem, 14rem) minmax(0, 1fr);
  align-items: start;
}

.catalog-detail__summary > div:last-child {
  min-width: 0;
}

.catalog-detail__media {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--pj-surface-soft);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.catalog-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: var(--pj-space-5) 0 0;
}

.catalog-facts div {
  min-width: 0;
  padding: var(--pj-space-4) var(--pj-space-4) var(--pj-space-4) 0;
  border-top: 1px solid var(--pj-border-subtle);
}

.catalog-facts div:nth-child(even) {
  padding-inline: var(--pj-space-4) 0;
  border-left: 1px solid var(--pj-border-subtle);
}

.catalog-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.catalog-facts dd {
  margin: var(--pj-space-2) 0 0;
  overflow-wrap: anywhere;
}

.catalog-contract-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-block: 1px solid var(--pj-border-subtle);
}

.catalog-contract-grid > div {
  min-width: 0;
  padding: var(--pj-space-5);
}

.catalog-contract-grid > div + div {
  border-left: 1px solid var(--pj-border-subtle);
}

.catalog-contract-grid span,
.catalog-contract-grid strong {
  display: block;
}

.catalog-contract-grid strong {
  margin-top: var(--pj-space-2);
}

.catalog-contract-grid p {
  margin-bottom: 0;
  font-size: var(--pj-font-size-sm);
}

@media (max-width: 72rem) {
  .catalog-detail { max-width: none; }
}

@media (max-width: 48rem) {
  .catalog-filters-pane,
  .catalog-list-pane,
  .catalog-detail-pane { padding: var(--pj-space-5); }

  .catalog-detail__summary,
  .catalog-contract-grid { grid-template-columns: minmax(0, 1fr); }

  .catalog-contract-grid > div + div { border-left: 0; border-top: 1px solid var(--pj-border-subtle); }
}

@media (max-width: 32rem) {
  .catalog-panel-header,
  .catalog-detail__header,
  .catalog-detail__section > header,
  .catalog-pagination { align-items: flex-start; flex-direction: column; }

  .catalog-list button { grid-template-columns: 4.25rem minmax(0, 1fr); }
  .catalog-list button > b { grid-column: 2; }
  .catalog-facts { grid-template-columns: minmax(0, 1fr); }
  .catalog-facts div,
  .catalog-facts div:nth-child(even) { padding-inline: 0; border-left: 0; }
}
</style>
