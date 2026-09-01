<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import {
  formatMoney,
  parseSpecification,
  type ProductDetail,
  type ProductSku,
} from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjPageContainer,
  PjResponsiveImage,
  PjStatusNotice,
  PjSurface,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

import {
  catalogApi,
  CatalogAsyncState,
} from "../entities/catalog";
import { useBagStore } from "../entities/guest-bag";
import type { ReviewAccessContext } from "../entities/product-review";
import { useSessionStore } from "../features/customer-session";
import { ProductReviewsSection } from "../features/product-reviews";

const route = useRoute();
const router = useRouter();
const bag = useBagStore();
const session = useSessionStore();
const product = ref<ProductDetail | null>(null);
const selectedSkuId = ref<string | null>(null);
const quantity = ref(1);
const loading = ref(true);
const error = ref<string | null>(null);
const added = ref(false);
const reviewAccess = computed<ReviewAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const activeSkus = computed(() => product.value?.skus.filter(
  (sku) => sku.status === "ACTIVE",
) ?? []);
const selectedSku = computed<ProductSku | null>(() => (
  activeSkus.value.find((sku) => sku.id === selectedSkuId.value)
  ?? activeSkus.value[0]
  ?? null
));
const selectedSpecs = computed(() => (
  selectedSku.value ? parseSpecification(selectedSku.value.specJson) : []
));
const media = computed(() => product.value?.media.filter((item) => item.url) ?? []);
const imageIndex = computed(() => {
  const raw = Number(route.query.image ?? 1);
  return Number.isInteger(raw) && raw > 0 && raw <= media.value.length ? raw - 1 : 0;
});
const currentMedia = computed(() => media.value[imageIndex.value] ?? null);
async function load() {
  const productId = String(route.params.productId ?? "");
  loading.value = true;
  error.value = null;
  added.value = false;
  try {
    product.value = await catalogApi.getProduct(productId);
    selectedSkuId.value = product.value.skus.find((sku) => sku.status === "ACTIVE")?.id ?? null;
    document.title = `${product.value.title}｜素简记`;
  } catch (cause) {
    product.value = null;
    error.value = cause instanceof Error ? cause.message : "暂时无法读取商品详情。";
  } finally {
    loading.value = false;
  }
}

function selectImage(index: number) {
  router.replace({
    query: {
      ...route.query,
      image: String(index + 1),
    },
  });
}

function addToBag() {
  if (!product.value || !selectedSku.value) {
    return;
  }
  bag.addItem({
    productId: product.value.id,
    skuId: selectedSku.value.id,
    productTitle: product.value.title,
    skuName: selectedSku.value.name,
    unitPrice: String(selectedSku.value.salePrice),
    quantity: quantity.value,
    coverUrl: currentMedia.value?.url ?? product.value.media.find((item) => item.url)?.url ?? null,
  });
  added.value = true;
}

onMounted(load);
watch(() => route.params.productId, load);
</script>

<template>
  <PjPageContainer as="section" class="product-detail-page">
    <CatalogAsyncState :loading="loading" :error="error" @retry="load">
      <template v-if="product">
        <nav class="content-path" aria-label="当前位置">
          <RouterLink to="/">素简记</RouterLink>
          <span aria-hidden="true">/</span>
          <RouterLink
            :to="{ name: 'products', query: { category: product.category.slug } }"
          >
            {{ product.category.name }}
          </RouterLink>
          <span aria-hidden="true">/</span>
          <span>{{ product.title }}</span>
        </nav>

        <div class="product-detail">
          <div class="product-media">
            <PjSurface tone="media" padding="none" class="product-media__main">
              <PjResponsiveImage
                v-if="currentMedia?.url"
                v-bind="resolveCatalogImageDelivery(currentMedia.url)"
                :alt="`${product.title}，图片 ${imageIndex + 1}`"
                sizes="(max-width: 48rem) calc(100vw - 2rem), 55vw"
                loading="eager"
                decoding="async"
                fetch-priority="high"
              />
              <div v-else class="product-media__placeholder">
                <span>{{ product.category.name }}</span>
                <strong>{{ product.title }}</strong>
              </div>
            </PjSurface>
            <div v-if="media.length > 1" class="product-media__list" aria-label="商品图片">
              <button
                v-for="(item, index) in media"
                :key="item.id"
                type="button"
                :aria-current="index === imageIndex ? 'true' : undefined"
                :aria-label="`查看第 ${index + 1} 张图片`"
                @click="selectImage(index)"
              >
                <PjResponsiveImage
                  v-bind="resolveCatalogImageDelivery(item.url ?? '')"
                  alt=""
                  sizes="72px"
                  loading="lazy"
                  decoding="async"
                />
              </button>
            </div>
          </div>

          <div class="product-purchase">
            <p class="product-context">
              {{ product.category.name }} · {{ product.brand.name }}
            </p>
            <h1>{{ product.title }}</h1>
            <p v-if="product.subtitle" class="product-lead">{{ product.subtitle }}</p>
            <p class="product-price">
              {{ formatMoney(selectedSku?.salePrice) }}
            </p>

            <fieldset class="sku-selector">
              <legend>选择规格</legend>
              <label
                v-for="sku in product.skus"
                :key="sku.id"
                :class="{ 'is-unavailable': sku.status !== 'ACTIVE' }"
              >
                <input
                  v-model="selectedSkuId"
                  type="radio"
                  name="sku"
                  :value="sku.id"
                  :disabled="sku.status !== 'ACTIVE'"
                />
                <span>
                  <strong>{{ sku.name }}</strong>
                  <small>{{ sku.status === "ACTIVE" ? formatMoney(sku.salePrice) : "暂时不可选" }}</small>
                </span>
              </label>
            </fieldset>

            <dl v-if="selectedSpecs.length" class="spec-list">
              <div v-for="entry in selectedSpecs" :key="entry.label">
                <dt>{{ entry.label }}</dt>
                <dd>{{ entry.value }}</dd>
              </div>
            </dl>

            <div class="purchase-action">
              <p>
                已选 {{ selectedSku?.name ?? "请先选择可用规格" }}，{{ quantity }} 件
              </p>
              <PjActionGroup align="between">
                <strong>{{ formatMoney(selectedSku?.salePrice) }}</strong>
                <PjButton :disabled="!selectedSku" @click="addToBag">
                  加入购物袋
                </PjButton>
              </PjActionGroup>
              <p class="purchase-note">加入购物袋不锁定库存，结算时重新校验。</p>
            </div>

            <PjStatusNotice v-if="added" tone="success" title="已放入购物袋">
              <p>商品已保存在当前设备的购物袋中。</p>
              <template #actions>
                <RouterLink class="text-action" to="/bag">查看购物袋</RouterLink>
              </template>
            </PjStatusNotice>
          </div>
        </div>

        <section class="product-information" aria-labelledby="product-information-title">
          <div>
            <p class="product-context">商品说明</p>
            <h2 id="product-information-title">购买之前，先把事实看清。</h2>
          </div>
          <div class="product-description">
            <p>{{ product.description || "当前商品尚未补充完整说明。" }}</p>
            <dl>
              <div>
                <dt>当前版本</dt>
                <dd>版本 {{ product.version }}</dd>
              </div>
              <div>
                <dt>商品状态</dt>
                <dd>{{ product.status === "ACTIVE" ? "正在销售" : product.status }}</dd>
              </div>
              <div>
                <dt>价格说明</dt>
                <dd>以所选 SKU 与结算时服务端价格为准。</dd>
              </div>
            </dl>
          </div>
        </section>

        <ProductReviewsSection :access="reviewAccess" :product-id="product.id" />
      </template>
    </CatalogAsyncState>
  </PjPageContainer>
</template>

<style scoped>
.product-detail-page {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.product-detail {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(22rem, 0.72fr);
  gap: clamp(2rem, 6vw, 7rem);
  align-items: start;
}

.product-media {
  min-width: 0;
}

.product-media__main {
  aspect-ratio: 4 / 5;
  overflow: hidden;
}

.product-media__main img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-media__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background:
    linear-gradient(
      145deg,
      var(--pj-surface-media-muted) 0%,
      var(--pj-surface-media-highlight) 58%,
      var(--pj-surface-media-shadow) 100%
    );
  color: var(--pj-text-secondary);
}

.product-media__placeholder strong {
  margin-top: var(--pj-space-3);
  color: var(--pj-text-primary);
  font-size: var(--pj-font-size-lg);
}

.product-media__list {
  display: flex;
  gap: var(--pj-space-3);
  margin-top: var(--pj-space-3);
}

.product-media__list button {
  width: 4.5rem;
  aspect-ratio: 1;
  padding: 0;
  border: 1px solid transparent;
  background: var(--pj-surface-media);
  cursor: pointer;
}

.product-media__list button[aria-current="true"] {
  border-color: var(--pj-brand-primary-hover);
}

.product-media__list img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-purchase {
  position: sticky;
  top: var(--pj-space-5);
  min-width: 0;
}

.product-context {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.product-purchase h1 {
  margin: 0;
  font-size: clamp(2.25rem, 4.4vw, 4.4rem);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: 1.02;
}

.product-lead {
  max-width: 34rem;
  margin-block: var(--pj-space-4);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-lg);
}

.product-price {
  margin: var(--pj-space-6) 0 0;
  font-size: clamp(1.5rem, 2.5vw, 2.25rem);
  font-variant-numeric: tabular-nums;
}

.sku-selector {
  margin: var(--pj-space-6) 0;
  padding: 0;
  border: 0;
}

.sku-selector legend {
  margin-bottom: var(--pj-space-3);
  font-weight: 650;
}

.sku-selector label {
  display: block;
  border-top: 1px solid var(--pj-border-subtle);
}

.sku-selector label:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.sku-selector label > span {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding: var(--pj-space-4) 0;
  cursor: pointer;
}

.sku-selector input {
  position: absolute;
  opacity: 0;
}

.sku-selector input:checked + span {
  color: var(--pj-brand-primary-hover);
  box-shadow: inset 0 -2px var(--pj-brand-primary);
}

.sku-selector input:focus-visible + span {
  outline: 0.15rem solid var(--pj-focus-ring);
  outline-offset: 0.15rem;
}

.sku-selector small {
  color: var(--pj-text-secondary);
}

.sku-selector .is-unavailable {
  color: var(--pj-text-secondary);
  text-decoration: line-through;
}

.spec-list,
.product-description dl {
  margin: 0;
}

.spec-list > div,
.product-description dl > div {
  display: grid;
  grid-template-columns: 8rem minmax(0, 1fr);
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
}

.spec-list dt,
.product-description dt {
  color: var(--pj-text-secondary);
}

.spec-list dd,
.product-description dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.purchase-action {
  display: grid;
  gap: var(--pj-space-3);
  margin-top: var(--pj-space-6);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-strong);
}

.purchase-action > p {
  margin: 0;
}

.purchase-action > p:first-child,
.purchase-note {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.purchase-action strong {
  font-size: var(--pj-font-size-lg);
}

.product-purchase :deep(.pj-status-notice) {
  margin-top: var(--pj-space-5);
}

.product-information {
  display: grid;
  grid-template-columns: minmax(16rem, 0.68fr) minmax(0, 1.32fr);
  gap: clamp(2rem, 6vw, 7rem);
  margin-top: var(--pj-space-8);
  padding-block: var(--pj-space-8);
  border-top: 1px solid var(--pj-border-subtle);
}

.product-information h2 {
  max-width: 15ch;
  margin: 0;
  font-size: clamp(1.8rem, 3vw, 3rem);
  font-weight: 520;
  line-height: var(--pj-line-height-tight);
}

.product-description > p {
  margin-top: 0;
  white-space: pre-line;
}

@media (max-width: 64rem) {
  .product-detail {
    grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.9fr);
    gap: var(--pj-space-6);
  }
}

@media (max-width: 48rem) {
  .product-detail,
  .product-information {
    grid-template-columns: 1fr;
  }

  .product-purchase {
    position: static;
  }

  .product-information {
    gap: var(--pj-space-6);
  }
}

@media (max-width: 32rem) {
  .product-detail-page {
    padding-top: var(--pj-space-5);
  }

  .product-purchase :deep(.pj-action-group) {
    align-items: stretch;
  }

  .product-purchase :deep(.pj-button) {
    width: 100%;
  }

  .spec-list > div,
  .product-description dl > div {
    grid-template-columns: 1fr;
    gap: var(--pj-space-1);
  }
}
</style>
