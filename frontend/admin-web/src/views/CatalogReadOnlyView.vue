<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";

import {
  formatMoney,
  type AdminProductSummary,
  type BusinessId,
  type CreateProductInput,
  type ProductDetail,
} from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjResponsiveImage,
  PjStatusNotice,
  resolveCatalogImageDelivery,
} from "@plain-journal/ui";

import {
  useAdminCatalogStore,
  type CatalogCommandPhase,
} from "../entities/admin-catalog";
import { ListWorkbench } from "../shared/ui";
import { useStaffSessionStore } from "../stores/session";

interface SkuEditForm {
  id: BusinessId;
  skuCode: string;
  name: string;
  specJson: string;
  salePrice: string;
  marketPrice: string;
  status: "ACTIVE" | "INACTIVE";
  expectedVersion: number;
}

const session = useStaffSessionStore();
const catalog = useAdminCatalogStore();
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.requestAuthority,
}));
const selectedProductId = ref<BusinessId | null>(null);
const failedImageIds = ref<Set<BusinessId>>(new Set());
const mediaFile = ref<File | null>(null);
const mediaSkuId = ref<BusinessId | "">("");
const mediaSortOrder = ref(0);
const skuForms = ref<SkuEditForm[]>([]);
const createForm = reactive({
  categoryId: "" as BusinessId | "",
  brandId: "" as BusinessId | "",
  title: "",
  subtitle: "",
  description: "",
  skuCode: "",
  skuName: "",
  specJson: "{}",
  salePrice: "",
  marketPrice: "",
});
const editForm = reactive({
  categoryId: "" as BusinessId | "",
  brandId: "" as BusinessId | "",
  title: "",
  subtitle: "",
  description: "",
  expectedVersion: 0,
});

const selectedSummary = computed<AdminProductSummary | null>(() =>
  catalog.products.find((product) => product.id === selectedProductId.value)
  ?? catalog.products[0]
  ?? null);
const busy = computed(() => catalog.commandPhase === "processing");

function noticeTone(phase: CatalogCommandPhase) {
  return {
    idle: "neutral",
    processing: "processing",
    unknown: "unknown",
    accepted: "success",
    rejected: "danger",
  }[phase] as "neutral" | "processing" | "unknown" | "success" | "danger";
}

function noticeTitle(phase: CatalogCommandPhase) {
  return {
    idle: "商品命令",
    processing: "命令正在处理中",
    unknown: "命令结果未知",
    accepted: "命令结果已确认",
    rejected: "命令未被接受",
  }[phase];
}

function hydrateProduct(product: ProductDetail | null) {
  if (!product) return;
  selectedProductId.value = product.id;
  editForm.categoryId = product.category.id;
  editForm.brandId = product.brand.id;
  editForm.title = product.title;
  editForm.subtitle = product.subtitle ?? "";
  editForm.description = product.description ?? "";
  editForm.expectedVersion = product.version;
  skuForms.value = product.skus.map((sku) => ({
    id: sku.id,
    skuCode: sku.skuCode,
    name: sku.name,
    specJson: sku.specJson,
    salePrice: String(sku.salePrice),
    marketPrice: sku.marketPrice == null ? "" : String(sku.marketPrice),
    status: sku.status,
    expectedVersion: sku.version,
  }));
  if (mediaSkuId.value
    && !product.skus.some((sku) => sku.id === mediaSkuId.value)) {
    mediaSkuId.value = "";
  }
}

async function selectProduct(product: AdminProductSummary) {
  selectedProductId.value = product.id;
  await catalog.loadProduct(accessContext.value, product.id);
}

async function refresh() {
  failedImageIds.value = new Set();
  await catalog.loadWorkspace(accessContext.value);
  if (selectedProductId.value) {
    await catalog.loadProduct(accessContext.value, selectedProductId.value);
  }
}

async function submitCreate() {
  if (!createForm.categoryId || !createForm.brandId) return;
  const input: CreateProductInput = {
    categoryId: createForm.categoryId,
    brandId: createForm.brandId,
    title: createForm.title.trim(),
    subtitle: createForm.subtitle.trim() || null,
    description: createForm.description.trim() || null,
    skus: [{
      skuCode: createForm.skuCode.trim(),
      name: createForm.skuName.trim(),
      specJson: createForm.specJson.trim(),
      salePrice: createForm.salePrice.trim(),
      marketPrice: createForm.marketPrice.trim() || null,
    }],
  };
  const created = await catalog.createProduct(accessContext.value, input);
  if (created) {
    hydrateProduct(created);
    createForm.title = "";
    createForm.subtitle = "";
    createForm.description = "";
    createForm.skuCode = "";
    createForm.skuName = "";
    createForm.specJson = "{}";
    createForm.salePrice = "";
    createForm.marketPrice = "";
  }
}

async function submitEdit() {
  const product = catalog.selectedProduct;
  if (!product || !editForm.categoryId || !editForm.brandId) return;
  await catalog.updateProduct(accessContext.value, product.id, {
    categoryId: editForm.categoryId,
    brandId: editForm.brandId,
    title: editForm.title.trim(),
    subtitle: editForm.subtitle.trim() || null,
    description: editForm.description.trim() || null,
    expectedVersion: editForm.expectedVersion,
  });
}

async function transitionProduct(publish: boolean) {
  const product = catalog.selectedProduct;
  if (!product) return;
  await catalog.setPublished(
    accessContext.value,
    product.id,
    product.version,
    publish,
  );
}

async function submitSku(form: SkuEditForm) {
  const product = catalog.selectedProduct;
  if (!product) return;
  await catalog.updateSku(accessContext.value, product.id, form.id, {
    name: form.name.trim(),
    specJson: form.specJson.trim(),
    salePrice: form.salePrice.trim(),
    marketPrice: form.marketPrice.trim() || null,
    status: form.status,
    expectedVersion: form.expectedVersion,
  });
}

function selectMediaFile(event: Event) {
  const input = event.target as HTMLInputElement;
  mediaFile.value = input.files?.[0] ?? null;
}

async function submitMedia() {
  const product = catalog.selectedProduct;
  if (!product || !mediaFile.value) return;
  await catalog.uploadMedia(
    accessContext.value,
    product.id,
    mediaFile.value,
    mediaSkuId.value || null,
    mediaSortOrder.value,
  );
}

function markImageFailed(productId: BusinessId) {
  failedImageIds.value = new Set([...failedImageIds.value, productId]);
}

function imageAvailable(product: AdminProductSummary): boolean {
  return Boolean(product.coverUrl && !failedImageIds.value.has(product.id));
}

function minimumPrice(product: AdminProductSummary): string {
  return product.minimumPrice == null ? "—" : formatMoney(product.minimumPrice);
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleTimeString("zh-CN") : "尚未读取";
}

watch(accessContext, (context, previous) => {
  catalog.synchronizeAccess(context);
  if (context.authorized && (
    !previous?.authorized
    || context.operatorId !== previous.operatorId
  )) {
    void catalog.loadWorkspace(context);
  }
});
watch(() => catalog.selectedProduct, hydrateProduct, { deep: true });
watch(
  () => catalog.products.map((product) => product.id),
  (ids) => {
    if (ids.length === 0) {
      selectedProductId.value = null;
      return;
    }
    if (!selectedProductId.value || !ids.includes(selectedProductId.value)) {
      const first = catalog.products[0];
      if (first) void selectProduct(first);
    }
  },
);

onMounted(async () => {
  catalog.synchronizeAccess(accessContext.value);
  await catalog.loadWorkspace(accessContext.value);
  const first = catalog.products[0];
  if (first) await selectProduct(first);
});
</script>

<template>
  <ListWorkbench label="商品经营工作区" class="catalog-workbench">
    <template #filters>
      <div class="catalog-pane catalog-controls">
        <header>
          <p class="eyebrow">Catalog owner workspace</p>
          <h1>商品经营</h1>
          <p>读取草稿、上架与下架事实；写操作只提交到 Catalog 所有者接口。</p>
        </header>

        <form class="catalog-form" @submit.prevent="catalog.applyFilters(accessContext)">
          <h2>筛选商品</h2>
          <PjField v-slot="{ describedBy }" label="关键词" for-id="catalog-keyword">
            <input id="catalog-keyword" v-model="catalog.query.keyword" class="pj-control" maxlength="80" :aria-describedby="describedBy" />
          </PjField>
          <PjField label="经营状态" for-id="catalog-status">
            <select id="catalog-status" v-model="catalog.query.status" class="pj-control">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="ACTIVE">已上架</option>
              <option value="INACTIVE">已下架</option>
            </select>
          </PjField>
          <PjField label="分类" for-id="catalog-category">
            <select id="catalog-category" v-model="catalog.query.categoryId" class="pj-control">
              <option value="">全部分类</option>
              <option v-for="category in catalog.categories" :key="category.id" :value="category.id">{{ category.name }}</option>
            </select>
          </PjField>
          <PjActionGroup :stack-on-compact="true">
            <PjButton type="submit" :loading="catalog.loadingProducts">应用筛选</PjButton>
            <PjButton type="button" variant="text" @click="catalog.clearFilters(accessContext)">清除</PjButton>
          </PjActionGroup>
        </form>

        <form class="catalog-form" @submit.prevent="submitCreate">
          <h2>新建草稿商品</h2>
          <PjField label="分类" for-id="create-category"><select id="create-category" v-model="createForm.categoryId" class="pj-control" required><option value="" disabled>选择分类</option><option v-for="item in catalog.categories" :key="item.id" :value="item.id">{{ item.name }}</option></select></PjField>
          <PjField label="品牌" for-id="create-brand"><select id="create-brand" v-model="createForm.brandId" class="pj-control" required><option value="" disabled>选择品牌</option><option v-for="item in catalog.brands" :key="item.id" :value="item.id">{{ item.name }}</option></select></PjField>
          <PjField label="标题" for-id="create-title"><input id="create-title" v-model="createForm.title" class="pj-control" maxlength="160" required /></PjField>
          <PjField label="副标题" for-id="create-subtitle"><input id="create-subtitle" v-model="createForm.subtitle" class="pj-control" maxlength="240" /></PjField>
          <PjField label="描述" for-id="create-description"><textarea id="create-description" v-model="createForm.description" class="pj-control" maxlength="10000" rows="3" /></PjField>
          <div class="catalog-pair">
            <PjField label="首个 SKU 编码" for-id="create-sku-code"><input id="create-sku-code" v-model="createForm.skuCode" class="pj-control" maxlength="64" required /></PjField>
            <PjField label="SKU 名称" for-id="create-sku-name"><input id="create-sku-name" v-model="createForm.skuName" class="pj-control" maxlength="160" required /></PjField>
          </div>
          <PjField label="规格 JSON" for-id="create-spec"><textarea id="create-spec" v-model="createForm.specJson" class="pj-control" maxlength="2000" rows="2" required /></PjField>
          <div class="catalog-pair">
            <PjField label="销售价" for-id="create-sale"><input id="create-sale" v-model="createForm.salePrice" class="pj-control" inputmode="decimal" required /></PjField>
            <PjField label="市场价" for-id="create-market"><input id="create-market" v-model="createForm.marketPrice" class="pj-control" inputmode="decimal" /></PjField>
          </div>
          <PjButton type="submit" :loading="busy" :disabled="Boolean(catalog.pendingCreate)">建立草稿</PjButton>
        </form>
      </div>
    </template>

    <template #list>
      <div class="catalog-pane catalog-list-pane">
        <header class="catalog-panel-header">
          <div><p class="eyebrow">Owner facts</p><h2>商品清单</h2></div>
          <small>{{ catalog.total }} 条 · {{ formatTime(catalog.refreshedAt) }}</small>
        </header>

        <PjStatusNotice v-if="catalog.categoriesError || catalog.brandsError" tone="danger" title="基础经营数据读取未完成" assertive><p>{{ catalog.categoriesError || catalog.brandsError }}</p></PjStatusNotice>
        <PjStatusNotice v-if="catalog.productsError" tone="danger" title="商品事实读取未完成" assertive><p>{{ catalog.productsError }}</p><p v-if="catalog.products.length">保留上一次已确认的 owner 事实，没有把失败伪装成空清单。</p></PjStatusNotice>
        <PjStatusNotice v-if="catalog.commandPhase !== 'idle' && catalog.commandMessage" :tone="noticeTone(catalog.commandPhase)" :title="noticeTitle(catalog.commandPhase)" :assertive="catalog.commandPhase === 'rejected'">
          <p>{{ catalog.commandMessage }}</p>
          <template v-if="catalog.pendingCreate" #actions>
            <PjButton type="button" variant="text" :loading="busy" @click="catalog.resolvePendingCreate(accessContext)">先查创建事实</PjButton>
            <PjButton type="button" :loading="busy" @click="catalog.retryPendingCreate(accessContext)">原样重放</PjButton>
          </template>
        </PjStatusNotice>

        <div v-if="catalog.loadingProducts && !catalog.products.length" class="catalog-empty" role="status">正在读取商品 owner 事实</div>
        <div v-else-if="!catalog.products.length" class="catalog-empty">当前范围没有商品</div>
        <ol v-else class="catalog-list">
          <li v-for="product in catalog.products" :key="product.id">
            <button type="button" :class="{ 'is-selected': selectedSummary?.id === product.id }" :aria-pressed="selectedSummary?.id === product.id" @click="selectProduct(product)">
              <span class="catalog-list__media" :class="{ 'is-empty': !imageAvailable(product) }">
                <PjResponsiveImage v-if="imageAvailable(product)" v-bind="resolveCatalogImageDelivery(product.coverUrl!)" :alt="`${product.title} 商品图`" sizes="76px" loading="lazy" @error="markImageFailed(product.id)" />
                <span v-else aria-hidden="true">无图片</span>
              </span>
              <span class="catalog-list__copy"><small>{{ product.status }} · v{{ product.version }}</small><strong>{{ product.title }}</strong><span>{{ product.brand.name }} · {{ product.category.name }}</span><code>{{ product.id }}</code></span>
              <b>{{ minimumPrice(product) }}</b>
            </button>
          </li>
        </ol>
        <footer v-if="catalog.total > 0" class="catalog-pagination">
          <span>显示 {{ catalog.visibleStart }}–{{ catalog.visibleEnd }} · 第 {{ catalog.query.page }} / {{ catalog.pageCount }} 页</span>
          <PjActionGroup><PjButton type="button" variant="text" :disabled="!catalog.hasPreviousPage" @click="catalog.goToPage(accessContext, catalog.query.page - 1)">上一页</PjButton><PjButton type="button" variant="text" :disabled="!catalog.hasNextPage" @click="catalog.goToPage(accessContext, catalog.query.page + 1)">下一页</PjButton></PjActionGroup>
        </footer>
        <PjButton type="button" variant="text" :loading="catalog.refreshing" @click="refresh">重新读取 owner 事实</PjButton>
      </div>
    </template>

    <template #detail>
      <div class="catalog-pane catalog-detail-pane">
        <PjStatusNotice v-if="catalog.detailError" tone="danger" title="商品详情读取未完成" assertive><p>{{ catalog.detailError }}</p></PjStatusNotice>
        <div v-if="catalog.loadingDetail && !catalog.selectedProduct" class="catalog-empty">正在读取完整商品事实</div>
        <article v-else-if="catalog.selectedProduct" class="catalog-detail">
          <header class="catalog-detail__header">
            <div><p class="eyebrow">商品 ID {{ catalog.selectedProduct.id }}</p><h2>{{ catalog.selectedProduct.title }}</h2><p>{{ catalog.selectedProduct.status }} · product v{{ catalog.selectedProduct.version }}</p></div>
            <PjActionGroup>
              <PjButton v-if="catalog.selectedProduct.status !== 'ACTIVE'" type="button" :loading="busy" @click="transitionProduct(true)">上架</PjButton>
              <PjButton v-if="catalog.selectedProduct.status === 'ACTIVE'" type="button" variant="secondary" :loading="busy" @click="transitionProduct(false)">下架</PjButton>
            </PjActionGroup>
          </header>

          <form class="catalog-form catalog-detail__section" @submit.prevent="submitEdit">
            <header><h3>基本信息</h3><p>提交时携带当前 product version</p></header>
            <div class="catalog-pair"><PjField label="分类" for-id="edit-category"><select id="edit-category" v-model="editForm.categoryId" class="pj-control"><option v-for="item in catalog.categories" :key="item.id" :value="item.id">{{ item.name }}</option></select></PjField><PjField label="品牌" for-id="edit-brand"><select id="edit-brand" v-model="editForm.brandId" class="pj-control"><option v-for="item in catalog.brands" :key="item.id" :value="item.id">{{ item.name }}</option></select></PjField></div>
            <PjField label="标题" for-id="edit-title"><input id="edit-title" v-model="editForm.title" class="pj-control" maxlength="160" required /></PjField>
            <PjField label="副标题" for-id="edit-subtitle"><input id="edit-subtitle" v-model="editForm.subtitle" class="pj-control" maxlength="240" /></PjField>
            <PjField label="描述" for-id="edit-description"><textarea id="edit-description" v-model="editForm.description" class="pj-control" maxlength="10000" rows="4" /></PjField>
            <PjButton type="submit" :loading="busy">保存基本信息</PjButton>
          </form>

          <section class="catalog-detail__section">
            <header><h3>SKU</h3><p>每条 SKU 独立携带版本</p></header>
            <form v-for="form in skuForms" :key="form.id" class="catalog-form catalog-sku" @submit.prevent="submitSku(form)">
              <strong>{{ form.skuCode }} · v{{ form.expectedVersion }}</strong>
              <div class="catalog-pair"><PjField :label="`${form.skuCode} 名称`" :for-id="`sku-name-${form.id}`"><input :id="`sku-name-${form.id}`" v-model="form.name" class="pj-control" required /></PjField><PjField label="状态" :for-id="`sku-status-${form.id}`"><select :id="`sku-status-${form.id}`" v-model="form.status" class="pj-control"><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option></select></PjField></div>
              <PjField label="规格 JSON" :for-id="`sku-spec-${form.id}`"><textarea :id="`sku-spec-${form.id}`" v-model="form.specJson" class="pj-control" rows="2" required /></PjField>
              <div class="catalog-pair"><PjField label="销售价" :for-id="`sku-sale-${form.id}`"><input :id="`sku-sale-${form.id}`" v-model="form.salePrice" class="pj-control" inputmode="decimal" required /></PjField><PjField label="市场价" :for-id="`sku-market-${form.id}`"><input :id="`sku-market-${form.id}`" v-model="form.marketPrice" class="pj-control" inputmode="decimal" /></PjField></div>
              <PjButton type="submit" variant="secondary" :loading="busy">保存 SKU</PjButton>
            </form>
          </section>

          <form class="catalog-form catalog-detail__section" @submit.prevent="submitMedia">
            <header><h3>商品图片</h3><p>先上传对象，再按同一 object key 确认</p></header>
            <PjField label="图片文件" for-id="catalog-media-file"><input id="catalog-media-file" class="pj-control" type="file" accept="image/jpeg,image/png,image/webp" required @change="selectMediaFile" /></PjField>
            <div class="catalog-pair"><PjField label="关联 SKU（可选）" for-id="catalog-media-sku"><select id="catalog-media-sku" v-model="mediaSkuId" class="pj-control"><option value="">SPU 主图</option><option v-for="sku in catalog.selectedProduct.skus" :key="sku.id" :value="sku.id">{{ sku.skuCode }}</option></select></PjField><PjField label="排序" for-id="catalog-media-sort"><input id="catalog-media-sort" v-model.number="mediaSortOrder" class="pj-control" type="number" min="0" max="100000" /></PjField></div>
            <PjButton type="submit" :loading="busy" :disabled="!mediaFile">上传并确认</PjButton>
            <ul v-if="catalog.selectedProduct.media.length" class="catalog-media-list"><li v-for="media in catalog.selectedProduct.media" :key="media.id"><code>{{ media.objectKey }}</code><span>{{ media.mimeType }} · {{ media.sizeBytes }} bytes · sort {{ media.sortOrder }}</span></li></ul>
          </form>
        </article>
        <div v-else class="catalog-empty"><strong>选择一条商品</strong><span>详情来自 Catalog owner API，不从公开摘要猜测。</span></div>
      </div>
    </template>
  </ListWorkbench>
</template>

<style scoped>
.catalog-workbench { --pj-focus-ring: var(--pj-brand-primary); --pj-color-focus: var(--pj-focus-ring); }
.catalog-pane { min-width: 0; padding: clamp(1rem, 2vw, 1.75rem); }
.catalog-controls, .catalog-list-pane, .catalog-detail, .catalog-detail-pane, .catalog-form, .catalog-detail__section { display: grid; align-content: start; gap: var(--pj-space-5); }
h1, h2, h3 { margin: 0; }
.catalog-controls > header p:last-child, .catalog-panel-header small, .catalog-detail__header p, .catalog-detail__section header p, .catalog-empty span { color: var(--pj-text-secondary); }
.catalog-form { padding-block: var(--pj-space-5); border-top: 1px solid var(--pj-border-subtle); }
.catalog-form h2 { font-size: var(--pj-font-size-md); }
.catalog-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--pj-space-4); }
.catalog-panel-header, .catalog-detail__header, .catalog-detail__section > header, .catalog-pagination { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--pj-space-4); }
.catalog-list { display: grid; margin: 0; padding: 0; list-style: none; }
.catalog-list li + li { border-top: 1px solid var(--pj-border-subtle); }
.catalog-list button { width: 100%; min-width: 0; display: grid; grid-template-columns: 4.75rem minmax(0, 1fr) auto; gap: var(--pj-space-4); padding: var(--pj-space-5) var(--pj-space-4); border: 0; color: inherit; font: inherit; text-align: left; background: transparent; cursor: pointer; }
.catalog-list button.is-selected { background: color-mix(in srgb, var(--pj-brand-primary) 7%, transparent); box-shadow: inset .2rem 0 var(--pj-brand-primary); }
.catalog-list__media { aspect-ratio: 1; display: grid; place-items: center; overflow: hidden; background: var(--pj-surface-soft); color: var(--pj-text-secondary); font-size: var(--pj-font-size-xs); }
.catalog-list__media.is-empty { border: 1px dashed var(--pj-border-subtle); }
.catalog-list__media :deep(.pj-responsive-image), .catalog-list__media :deep(.pj-responsive-image__image) { width: 100%; height: 100%; object-fit: cover; }
.catalog-list__copy, .catalog-list__copy small, .catalog-list__copy strong, .catalog-list__copy span, .catalog-list__copy code { min-width: 0; display: block; }
.catalog-list__copy small, .catalog-list__copy span, .catalog-list__copy code { color: var(--pj-text-secondary); }
.catalog-list__copy strong { margin-top: var(--pj-space-1); }
.catalog-list__copy span, .catalog-list__copy code { margin-top: var(--pj-space-2); overflow-wrap: anywhere; }
.catalog-list button > b { color: var(--pj-brand-primary); white-space: nowrap; }
.catalog-pagination { padding-top: var(--pj-space-5); border-top: 1px solid var(--pj-border-subtle); color: var(--pj-text-secondary); font-size: var(--pj-font-size-sm); }
.catalog-empty { min-height: 10rem; display: grid; place-content: center; gap: var(--pj-space-2); text-align: center; }
.catalog-detail__header, .catalog-detail__section { padding-bottom: var(--pj-space-5); border-bottom: 1px solid var(--pj-border-subtle); }
.catalog-sku { padding: var(--pj-space-4); border: 1px solid var(--pj-border-subtle); }
.catalog-media-list { display: grid; gap: var(--pj-space-2); margin: 0; padding: 0; list-style: none; }
.catalog-media-list li { display: grid; gap: var(--pj-space-1); overflow-wrap: anywhere; }
.catalog-media-list span { color: var(--pj-text-secondary); font-size: var(--pj-font-size-xs); }
@media (max-width: 48rem) { .catalog-pair { grid-template-columns: minmax(0, 1fr); } .catalog-pane { padding: var(--pj-space-5); } }
@media (max-width: 32rem) { .catalog-panel-header, .catalog-detail__header, .catalog-detail__section > header, .catalog-pagination { flex-direction: column; } .catalog-list button { grid-template-columns: 4.25rem minmax(0, 1fr); } .catalog-list button > b { grid-column: 2; } }
</style>
