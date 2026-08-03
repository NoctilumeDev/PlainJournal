import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  createApiClient,
  createCatalogApi,
  type Brand,
  type BusinessId,
  type CatalogApi,
  type Category,
  type PageResponse,
  type ProductQuery,
  type ProductSummary,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

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

export class CatalogAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的目录读取结果不会写入当前页面。");
    this.name = "CatalogAccessChangedError";
  }
}

export class CatalogProjectionContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CatalogProjectionContractError";
  }
}

function isActiveContext(
  context: AdminCatalogAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && typeof context.operatorId === "string"
    && context.operatorId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function createApi(): CatalogApi {
  return createCatalogApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
  }));
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
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

function validMoney(value: unknown): value is string | number {
  return (typeof value === "string" && /^\d+(?:\.\d{1,2})?$/u.test(value))
    || (typeof value === "number" && Number.isFinite(value) && value >= 0);
}

function validProduct(value: unknown): value is ProductSummary {
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
    && "category" in value
    && validCategory(value.category)
    && "brand" in value
    && validBrand(value.brand)
    && "minimumPrice" in value
    && validMoney(value.minimumPrice)
    && "coverUrl" in value
    && (value.coverUrl === null || typeof value.coverUrl === "string"),
  );
}

function validateCategories(value: Category[]): Category[] {
  if (!Array.isArray(value) || !value.every(validCategory)) {
    throw new CatalogProjectionContractError(
      "Catalog 分类投影缺少稳定字符串 ID 或必要字段。",
    );
  }
  const unique = new Map(value.map((category) => [category.id, category]));
  return [...unique.values()].sort((left, right) =>
    left.sortOrder - right.sortOrder
    || left.name.localeCompare(right.name));
}

function validateProductsPage(
  value: PageResponse<ProductSummary>,
  expectedPage: number,
  expectedSize: number,
): PageResponse<ProductSummary> {
  if (
    !value
    || typeof value !== "object"
    || !Array.isArray(value.items)
    || !value.items.every(validProduct)
    || !Number.isInteger(value.page)
    || value.page !== expectedPage
    || !Number.isInteger(value.size)
    || value.size !== expectedSize
    || !Number.isInteger(value.total)
    || value.total < 0
    || value.items.length > expectedSize
    || value.items.length > value.total
    || new Set(value.items.map((product) => product.id)).size
      !== value.items.length
  ) {
    throw new CatalogProjectionContractError(
      "Catalog 商品分页投影与当前查询或字符串身份契约不一致。",
    );
  }
  return value;
}

export const useAdminCatalogStore = defineStore("admin-catalog", () => {
  const categories = ref<Category[]>([]);
  const products = ref<ProductSummary[]>([]);
  const query = reactive({
    keyword: "",
    categoryId: "",
    page: 1,
    size: 20,
  });
  const total = ref(0);
  const loadingCategories = ref(false);
  const loadingProducts = ref(false);
  const categoriesError = ref<string | null>(null);
  const productsError = ref<string | null>(null);
  const refreshedAt = ref<string | null>(null);
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let categoriesRevision = 0;
  let productsRevision = 0;

  const pageCount = computed(() =>
    Math.max(1, Math.ceil(total.value / query.size)));
  const hasPreviousPage = computed(() => query.page > 1);
  const hasNextPage = computed(() => query.page < pageCount.value);
  const visibleStart = computed(() =>
    products.value.length === 0
      ? 0
      : ((query.page - 1) * query.size) + 1);
  const visibleEnd = computed(() =>
    products.value.length === 0
      ? 0
      : visibleStart.value + products.value.length - 1);
  const refreshing = computed(() =>
    loadingCategories.value || loadingProducts.value);

  function activeAccess(
    context: AdminCatalogAccessContext,
  ): ActiveAccess | null {
    if (!isActiveContext(context)) {
      return null;
    }
    return {
      operatorId: context.operatorId,
      accessToken: context.accessToken,
      revision: accessRevision,
      api: createApi(),
    };
  }

  function accessIsCurrent(access: ActiveAccess): boolean {
    return access.revision === accessRevision
      && access.operatorId === activeOperatorId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveAccess) {
    if (!accessIsCurrent(access)) {
      throw new CatalogAccessChangedError();
    }
  }

  function synchronizeAccess(context: AdminCatalogAccessContext) {
    const nextOperatorId = isActiveContext(context) ? context.operatorId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const operatorChanged = activeOperatorId.value !== nextOperatorId;
    const accessChanged = operatorChanged || activeAccessToken !== nextAccessToken;
    if (!accessChanged) {
      return activeAccess(context);
    }

    activeOperatorId.value = nextOperatorId;
    activeAccessToken = nextAccessToken;
    accessRevision += 1;
    categoriesRevision += 1;
    productsRevision += 1;
    loadingCategories.value = false;
    loadingProducts.value = false;

    if (operatorChanged) {
      categories.value = [];
      products.value = [];
      query.keyword = "";
      query.categoryId = "";
      query.page = 1;
      total.value = 0;
      categoriesError.value = null;
      productsError.value = null;
      refreshedAt.value = null;
    }
    return activeAccess(context);
  }

  async function loadCategories(
    context: AdminCatalogAccessContext,
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      categoriesError.value = "当前会话无权打开商品目录工作区。";
      return;
    }
    const requestRevision = ++categoriesRevision;
    loadingCategories.value = true;
    categoriesError.value = null;
    try {
      const value = validateCategories(await access.api.listCategories());
      requireCurrent(access);
      if (requestRevision !== categoriesRevision) {
        return;
      }
      categories.value = value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === categoriesRevision) {
        categoriesError.value = errorMessage(
          cause,
          "Catalog 分类投影暂时无法读取。",
        );
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === categoriesRevision) {
        loadingCategories.value = false;
      }
    }
  }

  async function loadProducts(
    context: AdminCatalogAccessContext,
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      productsError.value = "当前会话无权打开商品目录工作区。";
      return;
    }
    const expectedPage = query.page;
    const expectedSize = query.size;
    const request: ProductQuery = {
      page: expectedPage,
      size: expectedSize,
    };
    if (query.categoryId) {
      request.categoryId = query.categoryId;
    }
    if (query.keyword.trim()) {
      request.keyword = query.keyword.trim();
    }
    const requestRevision = ++productsRevision;
    loadingProducts.value = true;
    productsError.value = null;
    try {
      const value = validateProductsPage(
        await access.api.listProducts(request),
        expectedPage,
        expectedSize,
      );
      requireCurrent(access);
      if (requestRevision !== productsRevision) {
        return;
      }
      products.value = value.items;
      total.value = value.total;
      refreshedAt.value = new Date().toISOString();
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === productsRevision) {
        productsError.value = errorMessage(
          cause,
          "Catalog 公开商品投影暂时无法读取。",
        );
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === productsRevision) {
        loadingProducts.value = false;
      }
    }
  }

  async function loadWorkspace(
    context: AdminCatalogAccessContext,
  ): Promise<void> {
    await Promise.all([
      loadCategories(context),
      loadProducts(context),
    ]);
  }

  async function applyFilters(
    context: AdminCatalogAccessContext,
  ): Promise<void> {
    query.keyword = query.keyword.trim();
    query.page = 1;
    await loadProducts(context);
  }

  async function clearFilters(
    context: AdminCatalogAccessContext,
  ): Promise<void> {
    query.keyword = "";
    query.categoryId = "";
    query.page = 1;
    await loadProducts(context);
  }

  async function goToPage(
    context: AdminCatalogAccessContext,
    page: number,
  ): Promise<void> {
    if (
      !Number.isInteger(page)
      || page < 1
      || page > pageCount.value
      || page === query.page
    ) {
      return;
    }
    query.page = page;
    await loadProducts(context);
  }

  return {
    categories,
    products,
    query,
    total,
    loadingCategories,
    loadingProducts,
    categoriesError,
    productsError,
    refreshedAt,
    pageCount,
    hasPreviousPage,
    hasNextPage,
    visibleStart,
    visibleEnd,
    refreshing,
    synchronizeAccess,
    loadCategories,
    loadProducts,
    loadWorkspace,
    applyFilters,
    clearFilters,
    goToPage,
  };
});
