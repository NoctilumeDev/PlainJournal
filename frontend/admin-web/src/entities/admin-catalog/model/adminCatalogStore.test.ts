import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  Category,
  PageResponse,
  ProductSummary,
} from "@plain-journal/foundation";

import {
  useAdminCatalogStore,
  type AdminCatalogAccessContext,
} from "./adminCatalogStore";

const ACCESS: AdminCatalogAccessContext = {
  authorized: true,
  operatorId: "2087000000000000001",
  accessToken: "operator-token",
};
const OTHER_ACCESS: AdminCatalogAccessContext = {
  authorized: true,
  operatorId: "2087000000000000002",
  accessToken: "admin-token",
};

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function categoryFixture(
  overrides: Partial<Category> = {},
): Category {
  return {
    id: "2087000000000000101",
    parentId: null,
    name: "随身用品",
    slug: "carry",
    sortOrder: 1,
    ...overrides,
  };
}

function productFixture(
  overrides: Partial<ProductSummary> = {},
): ProductSummary {
  return {
    id: "2087000000000000201",
    title: "青荷通勤袋",
    subtitle: "公开 ACTIVE 商品投影",
    category: categoryFixture(),
    brand: {
      id: "2087000000000000301",
      name: "素简记",
      slug: "plain-journal",
    },
    minimumPrice: "189.00",
    coverUrl: "/images/catalog/canvas-commuter-tote.png",
    ...overrides,
  };
}

function productPage(
  items: ProductSummary[],
  overrides: Partial<PageResponse<ProductSummary>> = {},
): PageResponse<ProductSummary> {
  return {
    items,
    page: 1,
    size: 20,
    total: items.length,
    ...overrides,
  };
}

describe("admin catalog entity", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads the public ACTIVE projection with string identities and page metadata", async () => {
    const product = productFixture();
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
    ) => {
      const path = new URL(String(input), "http://localhost").pathname;
      return path.endsWith("/categories")
        ? success([categoryFixture(), categoryFixture()])
        : success(productPage([product]));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    await store.loadWorkspace(ACCESS);

    expect(store.categories).toEqual([categoryFixture()]);
    expect(store.products).toEqual([product]);
    expect(store.products[0]?.id).toBe("2087000000000000201");
    expect(store.total).toBe(1);
    expect(store.visibleStart).toBe(1);
    expect(store.visibleEnd).toBe(1);
    expect(store.productsError).toBeNull();
  });

  it("resets to page one for filters and sends category and keyword as public query facts", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
    ) => {
      const url = new URL(String(input), "http://localhost");
      requests.push(url.search);
      const page = Number(url.searchParams.get("page"));
      return success(productPage(
        page === 1 ? [productFixture()] : [],
        { page, total: 25 },
      ));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    store.query.page = 2;
    store.query.categoryId = categoryFixture().id;
    store.query.keyword = "  青荷  ";

    await store.applyFilters(ACCESS);
    await store.goToPage(ACCESS, 2);

    expect(requests).toEqual([
      "?page=1&size=20&categoryId=2087000000000000101&keyword=%E9%9D%92%E8%8D%B7",
      "?page=2&size=20&categoryId=2087000000000000101&keyword=%E9%9D%92%E8%8D%B7",
    ]);
    expect(store.query.keyword).toBe("青荷");
    expect(store.query.page).toBe(2);
    expect(store.pageCount).toBe(2);
  });

  it("keeps the newest filter response when an older request finishes later", async () => {
    let resolveFirst!: (response: Response) => void;
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn((
      _input: RequestInfo | URL,
    ) => {
      attempts += 1;
      if (attempts === 1) {
        return new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        });
      }
      return Promise.resolve(success(productPage([
        productFixture({
          id: "2087000000000000202",
          title: "第二次筛选结果",
        }),
      ])));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    store.query.keyword = "旧条件";
    const first = store.loadProducts(ACCESS);
    store.query.keyword = "新条件";
    const second = store.loadProducts(ACCESS);
    await second;
    resolveFirst(success(productPage([
      productFixture({ title: "迟到的旧结果" }),
    ])));
    await first;

    expect(store.products).toHaveLength(1);
    expect(store.products[0]?.title).toBe("第二次筛选结果");
    expect(store.productsError).toBeNull();
  });

  it("does not write a completed public projection after the operator changes", async () => {
    let resolveProducts!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveProducts = resolve;
      })));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    const request = store.loadProducts(ACCESS);
    store.synchronizeAccess(OTHER_ACCESS);
    resolveProducts(success(productPage([productFixture()])));
    await request;

    expect(store.products).toEqual([]);
    expect(store.total).toBe(0);
    expect(store.productsError).toBeNull();
  });

  it("rejects a mismatched page contract without replacing known product facts", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      return attempts === 1
        ? success(productPage([productFixture()]))
        : success(productPage(
          [productFixture({ title: "错误页返回" })],
          { page: 2 },
        ));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    await store.loadProducts(ACCESS);
    await store.loadProducts(ACCESS);

    expect(store.products[0]?.title).toBe("青荷通勤袋");
    expect(store.productsError).toContain("分页投影");
  });

  it("preserves the last visible projection when a refresh receives 503", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      return attempts === 1
        ? success(productPage([productFixture()]))
        : failure(503, "SERVICE_UNAVAILABLE", "catalog replica unavailable");
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    await store.loadProducts(ACCESS);
    const refreshedAt = store.refreshedAt;
    await store.loadProducts(ACCESS);

    expect(store.products).toEqual([productFixture()]);
    expect(store.total).toBe(1);
    expect(store.refreshedAt).toBe(refreshedAt);
    expect(store.productsError).toBe("catalog replica unavailable");
  });
});
