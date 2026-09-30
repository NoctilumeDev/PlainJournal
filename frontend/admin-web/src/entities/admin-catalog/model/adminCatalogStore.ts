import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createCatalogApi,
  secureRandomUUID,
  type AdminProductQuery,
  type AdminProductStatus,
  type AdminProductSummary,
  type Brand,
  type BusinessId,
  type CatalogApi,
  type Category,
  type CreateProductInput,
  type PageResponse,
  type ProductDetail,
  type ProductMedia,
  type ProductSku,
  type UpdateProductInput,
  type UpdateProductSkuInput,
} from "@plain-journal/foundation";
import { createAuthenticatedApiClient } from "../../../shared/api";

const PENDING_CREATE_PREFIX =
  "plain-journal:admin-catalog:pending-create:v1:";

export type CatalogCommandPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

export interface AdminCatalogAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: CatalogApi;
}

export interface PendingProductCreate {
  commandId: string;
  input: CreateProductInput;
  createdAt: string;
}

export class CatalogAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的商品经营结果不会写入当前页面。");
    this.name = "CatalogAccessChangedError";
  }
}

export class CatalogProjectionContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CatalogProjectionContractError";
  }
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
}

function isActiveContext(
  context: AdminCatalogAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && isBusinessId(context.operatorId)
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function createApi(accessToken: string): CatalogApi {
  return createCatalogApi(createAuthenticatedApiClient(accessToken, 10000));
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

function resultMayBeUnknown(cause: unknown): boolean {
  if (cause instanceof CatalogProjectionContractError) {
    return true;
  }
  if (!(cause instanceof ApiError)) {
    return true;
  }
  return cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500);
}

function validCategory(value: unknown): value is Category {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "parentId" in value
    && (value.parentId === null || isBusinessId(value.parentId))
    && "name" in value
    && typeof value.name === "string"
    && value.name.length > 0
    && "slug" in value
    && typeof value.slug === "string"
    && value.slug.length > 0
    && "sortOrder" in value
    && Number.isInteger(value.sortOrder),
  );
}

function validBrand(value: unknown): value is Brand {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "name" in value
    && typeof value.name === "string"
    && value.name.length > 0
    && "slug" in value
    && typeof value.slug === "string"
    && value.slug.length > 0,
  );
}

function validMoney(value: unknown, nullable = false): boolean {
  return (nullable && value === null)
    || (typeof value === "string" && /^\d+(?:\.\d{1,2})?$/u.test(value))
    || (typeof value === "number" && Number.isFinite(value) && value >= 0);
}

function validAdminStatus(value: unknown): value is AdminProductStatus {
  return value === "DRAFT" || value === "ACTIVE" || value === "INACTIVE";
}

function validAdminProduct(value: unknown): value is AdminProductSummary {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "title" in value
    && typeof value.title === "string"
    && value.title.length > 0
    && "subtitle" in value
    && (value.subtitle === null || typeof value.subtitle === "string")
    && "status" in value
    && validAdminStatus(value.status)
    && "version" in value
    && Number.isInteger(value.version)
    && Number(value.version) >= 0
    && "category" in value
    && validCategory(value.category)
    && "brand" in value
    && validBrand(value.brand)
    && "minimumPrice" in value
    && validMoney(value.minimumPrice, true)
    && "coverUrl" in value
    && (value.coverUrl === null || typeof value.coverUrl === "string"),
  );
}

function validSku(value: unknown): value is ProductSku {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "skuCode" in value
    && typeof value.skuCode === "string"
    && value.skuCode.length > 0
    && "name" in value
    && typeof value.name === "string"
    && "specJson" in value
    && typeof value.specJson === "string"
    && "salePrice" in value
    && validMoney(value.salePrice)
    && "marketPrice" in value
    && validMoney(value.marketPrice, true)
    && "status" in value
    && (value.status === "ACTIVE" || value.status === "INACTIVE")
    && "version" in value
    && Number.isInteger(value.version),
  );
}

function validMedia(value: unknown): value is ProductMedia {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "skuId" in value
    && (value.skuId === null || isBusinessId(value.skuId))
    && "objectKey" in value
    && typeof value.objectKey === "string"
    && value.objectKey.length > 0
    && "mimeType" in value
    && typeof value.mimeType === "string"
    && "sizeBytes" in value
    && Number.isInteger(value.sizeBytes)
    && "sortOrder" in value
    && Number.isInteger(value.sortOrder)
    && "url" in value
    && (value.url === null || typeof value.url === "string"),
  );
}

function validateProductDetail(value: ProductDetail): ProductDetail {
  if (
    !value
    || typeof value !== "object"
    || !isBusinessId(value.id)
    || typeof value.title !== "string"
    || value.title.length === 0
    || !(value.subtitle === null || typeof value.subtitle === "string")
    || !(value.description === null || typeof value.description === "string")
    || !validAdminStatus(value.status)
    || !Number.isInteger(value.version)
    || !validCategory(value.category)
    || !validBrand(value.brand)
    || !Array.isArray(value.skus)
    || !value.skus.every(validSku)
    || !Array.isArray(value.media)
    || !value.media.every(validMedia)
  ) {
    throw new CatalogProjectionContractError(
      "Catalog 商品详情缺少稳定身份、版本或经营字段。",
    );
  }
  return value;
}

function validateCategories(value: Category[]): Category[] {
  if (!Array.isArray(value) || !value.every(validCategory)) {
    throw new CatalogProjectionContractError(
      "Catalog 分类投影缺少稳定字符串 ID 或必要字段。",
    );
  }
  return [...new Map(value.map((item) => [item.id, item])).values()]
    .sort((left, right) => left.sortOrder - right.sortOrder
      || left.name.localeCompare(right.name));
}

function validateBrands(value: Brand[]): Brand[] {
  if (!Array.isArray(value) || !value.every(validBrand)) {
    throw new CatalogProjectionContractError(
      "Catalog 品牌投影缺少稳定字符串 ID 或必要字段。",
    );
  }
  return [...new Map(value.map((item) => [item.id, item])).values()]
    .sort((left, right) => left.name.localeCompare(right.name));
}

function validateProductsPage(
  value: PageResponse<AdminProductSummary>,
  expectedPage: number,
  expectedSize: number,
  expectedStatus: AdminProductStatus | "",
): PageResponse<AdminProductSummary> {
  if (
    !value
    || typeof value !== "object"
    || !Array.isArray(value.items)
    || !value.items.every(validAdminProduct)
    || (expectedStatus !== ""
      && value.items.some((item) => item.status !== expectedStatus))
    || !Number.isInteger(value.page)
    || value.page !== expectedPage
    || !Number.isInteger(value.size)
    || value.size !== expectedSize
    || !Number.isInteger(value.total)
    || value.total < value.items.length
    || value.items.length > expectedSize
    || new Set(value.items.map((item) => item.id)).size !== value.items.length
  ) {
    throw new CatalogProjectionContractError(
      "Catalog 管理商品分页与当前查询、状态或字符串身份契约不一致。",
    );
  }
  return value;
}

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_CREATE_PREFIX}${operatorId}`;
}

function validCreateInput(value: unknown): value is CreateProductInput {
  if (!value || typeof value !== "object") {
    return false;
  }
  const input = value as Partial<CreateProductInput>;
  return isBusinessId(input.categoryId)
    && isBusinessId(input.brandId)
    && typeof input.title === "string"
    && input.title.length > 0
    && input.title.length <= 160
    && Array.isArray(input.skus)
    && input.skus.length > 0
    && input.skus.every((sku) => Boolean(
      sku
      && typeof sku.skuCode === "string"
      && sku.skuCode.length > 0
      && typeof sku.name === "string"
      && sku.name.length > 0
      && typeof sku.specJson === "string"
      && typeof sku.salePrice === "string"
      && sku.salePrice.length > 0,
    ));
}

function loadPending(operatorId: BusinessId): PendingProductCreate | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  try {
    const raw = localStorage.getItem(storageKey(operatorId));
    if (!raw) {
      return null;
    }
    const value = JSON.parse(raw) as Partial<PendingProductCreate>;
    return typeof value.commandId === "string"
      && value.commandId.length > 0
      && value.commandId.length <= 64
      && validCreateInput(value.input)
      && typeof value.createdAt === "string"
      && Number.isFinite(Date.parse(value.createdAt))
      ? value as PendingProductCreate
      : null;
  } catch {
    return null;
  }
}

function savePending(operatorId: BusinessId, command: PendingProductCreate | null) {
  if (typeof localStorage === "undefined") {
    return;
  }
  if (command) {
    localStorage.setItem(storageKey(operatorId), JSON.stringify(command));
  } else {
    localStorage.removeItem(storageKey(operatorId));
  }
}

function sameMoney(left: string | number, right: string): boolean {
  return Number(left) === Number(right);
}

function createResultMatches(
  product: ProductDetail,
  input: CreateProductInput,
): boolean {
  return product.category.id === input.categoryId
    && product.brand.id === input.brandId
    && product.title === input.title
    && (product.subtitle ?? null) === (input.subtitle ?? null)
    && (product.description ?? null) === (input.description ?? null)
    && product.skus.length === input.skus.length
    && input.skus.every((expected, index) => {
      const actual = product.skus[index];
      return actual?.skuCode === expected.skuCode
        && actual.name === expected.name
        && actual.specJson === expected.specJson
        && sameMoney(actual.salePrice, expected.salePrice)
        && (actual.marketPrice === null && expected.marketPrice == null
          || actual.marketPrice !== null
            && expected.marketPrice != null
            && sameMoney(actual.marketPrice, expected.marketPrice));
    });
}

async function uploadObject(intentUrl: string, file: File): Promise<void> {
  const controller = new AbortController();
  const timeout = globalThis.setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(intentUrl, {
      method: "PUT",
      headers: { "Content-Type": file.type },
      body: file,
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new ApiError(
        "http",
        `HTTP_${response.status}`,
        "对象存储明确拒绝了商品图片上传。",
        response.status,
      );
    }
  } catch (cause) {
    if (cause instanceof ApiError) throw cause;
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw new ApiError(
        "timeout",
        "UPLOAD_TIMEOUT",
        "图片上传响应未能确认。",
        undefined,
        { cause },
      );
    }
    throw new ApiError(
      "network",
      "UPLOAD_NETWORK_UNAVAILABLE",
      "暂时无法连接对象存储。",
      undefined,
      { cause },
    );
  } finally {
    globalThis.clearTimeout(timeout);
  }
}

export const useAdminCatalogStore = defineStore("admin-catalog", () => {
  const categories = ref<Category[]>([]);
  const brands = ref<Brand[]>([]);
  const products = ref<AdminProductSummary[]>([]);
  const selectedProduct = ref<ProductDetail | null>(null);
  const query = reactive({
    keyword: "",
    categoryId: "" as BusinessId | "",
    status: "" as AdminProductStatus | "",
    page: 1,
    size: 20,
  });
  const total = ref(0);
  const loadingCategories = ref(false);
  const loadingBrands = ref(false);
  const loadingProducts = ref(false);
  const loadingDetail = ref(false);
  const categoriesError = ref<string | null>(null);
  const brandsError = ref<string | null>(null);
  const productsError = ref<string | null>(null);
  const detailError = ref<string | null>(null);
  const refreshedAt = ref<string | null>(null);
  const activeOperatorId = ref<BusinessId | null>(null);
  const commandPhase = ref<CatalogCommandPhase>("idle");
  const commandMessage = ref<string | null>(null);
  const pendingCreate = ref<PendingProductCreate | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let categoriesRevision = 0;
  let brandsRevision = 0;
  let productsRevision = 0;
  let detailRevision = 0;
  let commandRevision = 0;
  let activeCreatePromise: Promise<ProductDetail | null> | null = null;

  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / query.size)));
  const hasPreviousPage = computed(() => query.page > 1);
  const hasNextPage = computed(() => query.page < pageCount.value);
  const visibleStart = computed(() => products.value.length === 0
    ? 0
    : ((query.page - 1) * query.size) + 1);
  const visibleEnd = computed(() => products.value.length === 0
    ? 0
    : visibleStart.value + products.value.length - 1);
  const refreshing = computed(() => loadingCategories.value
    || loadingBrands.value
    || loadingProducts.value
    || loadingDetail.value);

  function activeAccess(context: AdminCatalogAccessContext): ActiveAccess | null {
    if (!isActiveContext(context)) return null;
    return {
      operatorId: context.operatorId,
      accessToken: context.accessToken,
      revision: accessRevision,
      api: createApi(context.accessToken),
    };
  }

  function accessIsCurrent(access: ActiveAccess): boolean {
    return access.revision === accessRevision
      && access.operatorId === activeOperatorId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveAccess) {
    if (!accessIsCurrent(access)) throw new CatalogAccessChangedError();
  }

  function synchronizeAccess(context: AdminCatalogAccessContext) {
    const nextOperatorId = isActiveContext(context) ? context.operatorId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const operatorChanged = activeOperatorId.value !== nextOperatorId;
    const accessChanged = operatorChanged || activeAccessToken !== nextAccessToken;
    if (!accessChanged) return activeAccess(context);

    activeOperatorId.value = nextOperatorId;
    activeAccessToken = nextAccessToken;
    accessRevision += 1;
    categoriesRevision += 1;
    brandsRevision += 1;
    productsRevision += 1;
    detailRevision += 1;
    commandRevision += 1;
    activeCreatePromise = null;
    loadingCategories.value = false;
    loadingBrands.value = false;
    loadingProducts.value = false;
    loadingDetail.value = false;

    if (operatorChanged) {
      categories.value = [];
      brands.value = [];
      products.value = [];
      selectedProduct.value = null;
      query.keyword = "";
      query.categoryId = "";
      query.status = "";
      query.page = 1;
      total.value = 0;
      categoriesError.value = null;
      brandsError.value = null;
      productsError.value = null;
      detailError.value = null;
      refreshedAt.value = null;
      pendingCreate.value = nextOperatorId ? loadPending(nextOperatorId) : null;
      commandPhase.value = pendingCreate.value ? "unknown" : "idle";
      commandMessage.value = pendingCreate.value
        ? "发现一条结果未知的商品创建命令；原命令 ID 与完整载荷已恢复，只能先查询或原样重放。"
        : null;
    } else if (pendingCreate.value) {
      commandPhase.value = "unknown";
      commandMessage.value = "员工会话凭据已更新，商品创建命令仍保持结果未知。";
    }
    return activeAccess(context);
  }

  function requireAccess(context: AdminCatalogAccessContext, message: string): ActiveAccess | null {
    const access = synchronizeAccess(context);
    if (!access) {
      commandPhase.value = "rejected";
      commandMessage.value = message;
    }
    return access;
  }

  async function loadCategories(context: AdminCatalogAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      categoriesError.value = "当前会话无权读取商品经营工作区。";
      return;
    }
    const revision = ++categoriesRevision;
    loadingCategories.value = true;
    categoriesError.value = null;
    try {
      const value = validateCategories(await access.api.listCategories());
      requireCurrent(access);
      if (revision === categoriesRevision) categories.value = value;
    } catch (cause) {
      if (accessIsCurrent(access) && revision === categoriesRevision) {
        categoriesError.value = errorMessage(cause, "Catalog 分类暂时无法读取。");
      }
    } finally {
      if (accessIsCurrent(access) && revision === categoriesRevision) {
        loadingCategories.value = false;
      }
    }
  }

  async function loadBrands(context: AdminCatalogAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      brandsError.value = "当前会话无权读取商品经营工作区。";
      return;
    }
    const revision = ++brandsRevision;
    loadingBrands.value = true;
    brandsError.value = null;
    try {
      const value = validateBrands(await access.api.listBrands());
      requireCurrent(access);
      if (revision === brandsRevision) brands.value = value;
    } catch (cause) {
      if (accessIsCurrent(access) && revision === brandsRevision) {
        brandsError.value = errorMessage(cause, "Catalog 品牌暂时无法读取。");
      }
    } finally {
      if (accessIsCurrent(access) && revision === brandsRevision) {
        loadingBrands.value = false;
      }
    }
  }

  async function loadProducts(context: AdminCatalogAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      productsError.value = "当前会话无权读取商品经营工作区。";
      return;
    }
    const expectedPage = query.page;
    const expectedSize = query.size;
    const expectedStatus = query.status;
    const request: AdminProductQuery = { page: expectedPage, size: expectedSize };
    if (expectedStatus) request.status = expectedStatus;
    if (query.categoryId) request.categoryId = query.categoryId;
    if (query.keyword.trim()) request.keyword = query.keyword.trim();
    const revision = ++productsRevision;
    loadingProducts.value = true;
    productsError.value = null;
    try {
      const value = validateProductsPage(
        await access.api.listAdminProducts(request),
        expectedPage,
        expectedSize,
        expectedStatus,
      );
      requireCurrent(access);
      if (revision === productsRevision) {
        products.value = value.items;
        total.value = value.total;
        refreshedAt.value = new Date().toISOString();
      }
    } catch (cause) {
      if (accessIsCurrent(access) && revision === productsRevision) {
        productsError.value = errorMessage(cause, "Catalog 管理商品暂时无法读取。");
      }
    } finally {
      if (accessIsCurrent(access) && revision === productsRevision) {
        loadingProducts.value = false;
      }
    }
  }

  async function loadProduct(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
  ): Promise<ProductDetail | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      detailError.value = "当前会话无权读取商品详情。";
      return null;
    }
    const revision = ++detailRevision;
    loadingDetail.value = true;
    detailError.value = null;
    try {
      const product = validateProductDetail(await access.api.getAdminProduct(productId));
      requireCurrent(access);
      if (revision === detailRevision) {
        selectedProduct.value = product;
        return product;
      }
      return null;
    } catch (cause) {
      if (accessIsCurrent(access) && revision === detailRevision) {
        detailError.value = errorMessage(cause, "Catalog 商品详情暂时无法读取。");
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && revision === detailRevision) {
        loadingDetail.value = false;
      }
    }
  }

  async function loadWorkspace(context: AdminCatalogAccessContext) {
    await Promise.all([loadCategories(context), loadBrands(context), loadProducts(context)]);
  }

  async function applyFilters(context: AdminCatalogAccessContext) {
    query.keyword = query.keyword.trim();
    query.page = 1;
    await loadProducts(context);
  }

  async function clearFilters(context: AdminCatalogAccessContext) {
    query.keyword = "";
    query.categoryId = "";
    query.status = "";
    query.page = 1;
    await loadProducts(context);
  }

  async function goToPage(context: AdminCatalogAccessContext, page: number) {
    if (!Number.isInteger(page)
      || page < 1
      || page > pageCount.value
      || page === query.page) return;
    query.page = page;
    await loadProducts(context);
  }

  async function refreshProductFacts(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
  ) {
    await Promise.all([loadProducts(context), loadProduct(context, productId)]);
  }

  function settleCreateAccepted(access: ActiveAccess, product: ProductDetail) {
    pendingCreate.value = null;
    savePending(access.operatorId, null);
    selectedProduct.value = product;
    commandPhase.value = "accepted";
    commandMessage.value = `Catalog 已确认商品 ${product.id} 的创建事实。`;
  }

  async function executeCreate(
    access: ActiveAccess,
    command: PendingProductCreate,
  ): Promise<ProductDetail | null> {
    const revision = ++commandRevision;
    commandPhase.value = "processing";
    commandMessage.value = "商品创建命令正在等待 Catalog 确认。";
    try {
      const product = validateProductDetail(
        await access.api.createProduct(command.input, command.commandId),
      );
      requireCurrent(access);
      if (revision !== commandRevision) return null;
      if (!createResultMatches(product, command.input)) {
        throw new CatalogProjectionContractError(
          "Catalog 已响应，但创建结果与冻结的商品载荷不一致。",
        );
      }
      settleCreateAccepted(access, product);
      await loadProducts({
        authorized: true,
        operatorId: access.operatorId,
        accessToken: access.accessToken,
      });
      return product;
    } catch (cause) {
      if (!accessIsCurrent(access) || revision !== commandRevision) return null;
      if (resultMayBeUnknown(cause)) {
        commandPhase.value = "unknown";
        commandMessage.value =
          `${errorMessage(cause, "商品创建响应未能确认。")} `
          + "原命令 ID 与完整载荷已冻结；请先查询服务端事实，再决定是否原样重放。";
      } else {
        pendingCreate.value = null;
        savePending(access.operatorId, null);
        commandPhase.value = "rejected";
        commandMessage.value = errorMessage(cause, "Catalog 明确拒绝了商品创建命令。");
      }
      return null;
    }
  }

  function runCreate(
    access: ActiveAccess,
    command: PendingProductCreate,
  ): Promise<ProductDetail | null> {
    if (activeCreatePromise) return activeCreatePromise;
    pendingCreate.value = command;
    savePending(access.operatorId, command);
    const request = executeCreate(access, command);
    activeCreatePromise = request;
    const clear = () => {
      if (activeCreatePromise === request) activeCreatePromise = null;
    };
    void request.then(clear, clear);
    return request;
  }

  function createProduct(
    context: AdminCatalogAccessContext,
    input: CreateProductInput,
  ): Promise<ProductDetail | null> {
    const access = requireAccess(context, "当前会话无权创建商品。");
    if (!access) return Promise.resolve(null);
    if (pendingCreate.value) {
      commandPhase.value = "unknown";
      commandMessage.value = "已有一条结果未知的创建命令；必须先查询或原样重放，不能覆盖它。";
      return Promise.resolve(null);
    }
    return runCreate(access, {
      commandId: `catalog:create:${secureRandomUUID()}`,
      input: structuredClone(input),
      createdAt: new Date().toISOString(),
    });
  }

  async function resolvePendingCreate(
    context: AdminCatalogAccessContext,
  ): Promise<ProductDetail | null> {
    const access = requireAccess(context, "当前会话无权查询创建命令。");
    const command = pendingCreate.value;
    if (!access || !command) return null;
    const revision = ++commandRevision;
    commandPhase.value = "processing";
    commandMessage.value = "正在按原命令 ID 查询 Catalog 事实。";
    try {
      const product = validateProductDetail(
        await access.api.findCreatedProduct(command.commandId),
      );
      requireCurrent(access);
      if (revision !== commandRevision) return null;
      if (!createResultMatches(product, command.input)) {
        throw new CatalogProjectionContractError(
          "Catalog 返回的创建事实与冻结载荷不一致。",
        );
      }
      settleCreateAccepted(access, product);
      await loadProducts(context);
      return product;
    } catch (cause) {
      if (!accessIsCurrent(access) || revision !== commandRevision) return null;
      commandPhase.value = "unknown";
      commandMessage.value = cause instanceof ApiError
        && cause.code === "RESOURCE_NOT_FOUND"
        ? "Catalog 尚未观察到该命令的创建事实；原载荷仍被保留，可原样重放。"
        : `${errorMessage(cause, "创建事实查询未完成。")} 不能据此判断原命令失败。`;
      return null;
    }
  }

  function retryPendingCreate(
    context: AdminCatalogAccessContext,
  ): Promise<ProductDetail | null> {
    const access = requireAccess(context, "当前会话无权重放创建命令。");
    const command = pendingCreate.value;
    return access && command ? runCreate(access, command) : Promise.resolve(null);
  }

  async function executeProductCommand(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
    action: (api: CatalogApi) => Promise<ProductDetail>,
    recovered: (product: ProductDetail) => boolean,
    acceptedMessage: string,
  ): Promise<ProductDetail | null> {
    const access = requireAccess(context, "当前会话无权修改商品。");
    if (!access) return null;
    const revision = ++commandRevision;
    commandPhase.value = "processing";
    commandMessage.value = "商品命令正在等待 Catalog 确认。";
    try {
      const product = validateProductDetail(await action(access.api));
      requireCurrent(access);
      if (revision !== commandRevision) return null;
      selectedProduct.value = product;
      commandPhase.value = "accepted";
      commandMessage.value = acceptedMessage;
      await loadProducts(context);
      return product;
    } catch (cause) {
      if (!accessIsCurrent(access) || revision !== commandRevision) return null;
      if (!resultMayBeUnknown(cause)) {
        commandPhase.value = "rejected";
        commandMessage.value = errorMessage(cause, "Catalog 明确拒绝了商品命令。");
        return null;
      }
      commandPhase.value = "unknown";
      commandMessage.value =
        `${errorMessage(cause, "商品命令响应未能确认。")} 正在读取当前 owner 事实。`;
      try {
        const current = validateProductDetail(await access.api.getAdminProduct(productId));
        requireCurrent(access);
        if (revision !== commandRevision) return null;
        selectedProduct.value = current;
        if (recovered(current)) {
          commandPhase.value = "accepted";
          commandMessage.value = `${acceptedMessage}（通过 owner 详情恢复确认）`;
          await loadProducts(context);
          return current;
        }
        commandMessage.value =
          "当前 owner 事实尚不能证明原命令成功或失败；页面不会自动重放写请求。";
      } catch (readCause) {
        if (accessIsCurrent(access) && revision === commandRevision) {
          commandMessage.value =
            `${errorMessage(readCause, "当前 owner 事实读取失败。")} 原命令仍保持结果未知。`;
        }
      }
      return null;
    }
  }

  function updateProduct(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
    input: UpdateProductInput,
  ) {
    return executeProductCommand(
      context,
      productId,
      (api) => api.updateProduct(productId, input),
      (current) => current.id === productId
        && current.version === input.expectedVersion + 1
        && current.category.id === input.categoryId
        && current.brand.id === input.brandId
        && current.title === input.title
        && (current.subtitle ?? null) === (input.subtitle ?? null)
        && (current.description ?? null) === (input.description ?? null),
      `Catalog 已确认商品 ${productId} 的基本信息更新。`,
    );
  }

  function setPublished(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
    expectedVersion: number,
    publish: boolean,
  ) {
    const target: AdminProductStatus = publish ? "ACTIVE" : "INACTIVE";
    return executeProductCommand(
      context,
      productId,
      (api) => publish
        ? api.publishProduct(productId, expectedVersion)
        : api.unpublishProduct(productId, expectedVersion),
      (current) => current.id === productId
        && current.version === expectedVersion + 1
        && current.status === target,
      `Catalog 已确认商品 ${productId} 迁移为 ${target}。`,
    );
  }

  async function updateSku(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
    skuId: BusinessId,
    input: UpdateProductSkuInput,
  ): Promise<ProductSku | null> {
    const access = requireAccess(context, "当前会话无权修改 SKU。");
    if (!access) return null;
    const revision = ++commandRevision;
    commandPhase.value = "processing";
    commandMessage.value = "SKU 命令正在等待 Catalog 确认。";
    try {
      const sku = await access.api.updateProductSku(productId, skuId, input);
      if (!validSku(sku)
        || sku.id !== skuId
        || sku.version !== input.expectedVersion + 1) {
        throw new CatalogProjectionContractError(
          "Catalog 已响应，但 SKU 结果与冻结命令不一致。",
        );
      }
      requireCurrent(access);
      if (revision !== commandRevision) return null;
      commandPhase.value = "accepted";
      commandMessage.value = `Catalog 已确认 SKU ${skuId} 的更新。`;
      await refreshProductFacts(context, productId);
      return sku;
    } catch (cause) {
      if (!accessIsCurrent(access) || revision !== commandRevision) return null;
      if (!resultMayBeUnknown(cause)) {
        commandPhase.value = "rejected";
        commandMessage.value = errorMessage(cause, "Catalog 明确拒绝了 SKU 命令。");
        return null;
      }
      commandPhase.value = "unknown";
      commandMessage.value =
        `${errorMessage(cause, "SKU 命令响应未能确认。")} 页面不会自动重放写请求。`;
      return null;
    }
  }

  async function uploadMedia(
    context: AdminCatalogAccessContext,
    productId: BusinessId,
    file: File,
    skuId: BusinessId | null,
    sortOrder: number,
  ): Promise<ProductMedia | null> {
    const access = requireAccess(context, "当前会话无权上传商品图片。");
    if (!access) return null;
    const revision = ++commandRevision;
    commandPhase.value = "processing";
    commandMessage.value = "正在申请上传凭据并写入对象存储。";
    let objectKey: string | null = null;
    try {
      const intent = await access.api.createProductUploadIntent(
        productId,
        file.type,
        file.size,
      );
      objectKey = intent.objectKey;
      await uploadObject(intent.uploadUrl, file);
      const media = await access.api.confirmProductMedia(productId, {
        skuId,
        objectKey,
        sortOrder,
      });
      if (!validMedia(media) || media.objectKey !== objectKey) {
        throw new CatalogProjectionContractError(
          "Catalog 已响应，但媒体事实与上传对象不一致。",
        );
      }
      requireCurrent(access);
      if (revision !== commandRevision) return null;
      commandPhase.value = "accepted";
      commandMessage.value = `Catalog 已确认媒体 ${media.id}。`;
      await refreshProductFacts(context, productId);
      return media;
    } catch (cause) {
      if (!accessIsCurrent(access) || revision !== commandRevision) return null;
      if (!objectKey || !resultMayBeUnknown(cause)) {
        commandPhase.value = "rejected";
        commandMessage.value = errorMessage(cause, "媒体上传被明确拒绝。");
        return null;
      }
      try {
        const current = validateProductDetail(await access.api.getAdminProduct(productId));
        requireCurrent(access);
        if (revision !== commandRevision) return null;
        const media = current.media.find((item) => item.objectKey === objectKey);
        selectedProduct.value = current;
        if (media) {
          commandPhase.value = "accepted";
          commandMessage.value =
            `Catalog owner 详情已确认媒体 ${media.id}，响应丢失没有制造第二条记录。`;
          await loadProducts(context);
          return media;
        }
      } catch {
        // The original result remains unknown; the message below is authoritative.
      }
      commandPhase.value = "unknown";
      commandMessage.value =
        `对象 ${objectKey} 的确认结果未知；当前详情不能证明它已登记，页面不会换 object key 猜测重试。`;
      return null;
    }
  }

  return {
    categories,
    brands,
    products,
    selectedProduct,
    query,
    total,
    loadingCategories,
    loadingBrands,
    loadingProducts,
    loadingDetail,
    categoriesError,
    brandsError,
    productsError,
    detailError,
    refreshedAt,
    commandPhase,
    commandMessage,
    pendingCreate,
    pageCount,
    hasPreviousPage,
    hasNextPage,
    visibleStart,
    visibleEnd,
    refreshing,
    synchronizeAccess,
    loadCategories,
    loadBrands,
    loadProducts,
    loadProduct,
    loadWorkspace,
    applyFilters,
    clearFilters,
    goToPage,
    createProduct,
    resolvePendingCreate,
    retryPendingCreate,
    updateProduct,
    setPublished,
    updateSku,
    uploadMedia,
  };
});
