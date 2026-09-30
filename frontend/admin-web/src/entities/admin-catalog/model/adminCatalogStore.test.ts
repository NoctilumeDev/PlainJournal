import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  AdminProductSummary,
  Brand,
  Category,
  CreateProductInput,
  PageResponse,
  ProductDetail,
} from "@plain-journal/foundation";

import {
  useAdminCatalogStore,
  type AdminCatalogAccessContext,
} from "./adminCatalogStore";

const ACCESS: AdminCatalogAccessContext = {
  authorized: true,
  operatorId: "2087000000000000001",
  accessToken: "operator-authority-1",
};
const OTHER_ACCESS: AdminCatalogAccessContext = {
  authorized: true,
  operatorId: "2087000000000000002",
  accessToken: "admin-authority-2",
};

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-10-01T00:00:00Z",
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
    timestamp: "2026-10-01T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function categoryFixture(overrides: Partial<Category> = {}): Category {
  return {
    id: "2087000000000000101",
    parentId: null,
    name: "随身用品",
    slug: "carry",
    sortOrder: 1,
    ...overrides,
  };
}

function brandFixture(overrides: Partial<Brand> = {}): Brand {
  return {
    id: "2087000000000000301",
    name: "素简记",
    slug: "plain-journal",
    ...overrides,
  };
}

function productFixture(
  overrides: Partial<AdminProductSummary> = {},
): AdminProductSummary {
  return {
    id: "2087000000000000201",
    title: "青荷通勤袋",
    subtitle: "经营商品事实",
    status: "DRAFT",
    version: 0,
    category: categoryFixture(),
    brand: brandFixture(),
    minimumPrice: "189.00",
    coverUrl: null,
    ...overrides,
  };
}

function productDetailFixture(
  overrides: Partial<ProductDetail> = {},
): ProductDetail {
  return {
    id: "2087000000000000201",
    title: "青荷通勤袋",
    subtitle: "经营商品事实",
    description: "完整 owner 详情",
    status: "DRAFT",
    version: 0,
    category: categoryFixture(),
    brand: brandFixture(),
    skus: [{
      id: "2087000000000000401",
      skuCode: "BAG-BLACK",
      name: "黑色",
      specJson: "{}",
      salePrice: "189.00",
      marketPrice: null,
      status: "ACTIVE",
      version: 0,
    }],
    media: [],
    ...overrides,
  };
}

function productPage(
  items: AdminProductSummary[],
  overrides: Partial<PageResponse<AdminProductSummary>> = {},
): PageResponse<AdminProductSummary> {
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
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("loads owner product facts across all statuses with authenticated requests", async () => {
    const headers: Array<string | null> = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      headers.push(new Headers(init?.headers).get("Authorization"));
      if (url.pathname.endsWith("/categories")) return success([categoryFixture()]);
      if (url.pathname.endsWith("/brands")) return success([brandFixture()]);
      return success(productPage([productFixture()]));
    }));

    const store = useAdminCatalogStore();
    await store.loadWorkspace(ACCESS);

    expect(store.categories).toEqual([categoryFixture()]);
    expect(store.brands).toEqual([brandFixture()]);
    expect(store.products).toEqual([productFixture()]);
    expect(store.products[0]?.status).toBe("DRAFT");
    expect(store.total).toBe(1);
    expect(headers).toEqual([
      "Bearer operator-authority-1",
      "Bearer operator-authority-1",
      "Bearer operator-authority-1",
    ]);
  });

  it("resets to page one and sends owner status, category, and keyword filters", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      requests.push(url.search);
      const page = Number(url.searchParams.get("page"));
      return success(productPage(
        page === 1 ? [productFixture({ status: "INACTIVE" })] : [],
        { page, total: 25 },
      ));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    store.query.page = 2;
    store.query.status = "INACTIVE";
    store.query.categoryId = categoryFixture().id;
    store.query.keyword = "  青荷  ";

    await store.applyFilters(ACCESS);
    await store.goToPage(ACCESS, 2);

    expect(requests).toEqual([
      "?page=1&size=20&status=INACTIVE&categoryId=2087000000000000101&keyword=%E9%9D%92%E8%8D%B7",
      "?page=2&size=20&status=INACTIVE&categoryId=2087000000000000101&keyword=%E9%9D%92%E8%8D%B7",
    ]);
    expect(store.query.page).toBe(2);
    expect(store.pageCount).toBe(2);
  });

  it("keeps the newest filter response when an older request finishes later", async () => {
    let resolveFirst!: (response: Response) => void;
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(() => {
      attempts += 1;
      if (attempts === 1) {
        return new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        });
      }
      return Promise.resolve(success(productPage([
        productFixture({ id: "2087000000000000202", title: "第二次筛选结果" }),
      ])));
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    store.query.keyword = "旧条件";
    const first = store.loadProducts(ACCESS);
    store.query.keyword = "新条件";
    const second = store.loadProducts(ACCESS);
    await second;
    resolveFirst(success(productPage([productFixture({ title: "迟到的旧结果" })])));
    await first;

    expect(store.products[0]?.title).toBe("第二次筛选结果");
    expect(store.productsError).toBeNull();
  });

  it("does not let an old operator response write into the new session", async () => {
    let resolveProducts!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => {
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

  it("preserves known owner facts when a refresh is invalid or unavailable", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      if (attempts === 1) return success(productPage([productFixture()]));
      if (attempts === 2) {
        return success(productPage(
          [productFixture({ title: "错误页返回" })],
          { page: 2 },
        ));
      }
      return failure(503, "SERVICE_UNAVAILABLE", "catalog primary unavailable");
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    await store.loadProducts(ACCESS);
    await store.loadProducts(ACCESS);
    expect(store.products[0]?.title).toBe("青荷通勤袋");
    expect(store.productsError).toContain("分页");

    await store.loadProducts(ACCESS);
    expect(store.products[0]?.title).toBe("青荷通勤袋");
    expect(store.productsError).toBe("catalog primary unavailable");
  });

  it("retains an unknown create command, queries it, and replays the exact key and payload", async () => {
    const request: CreateProductInput = {
      categoryId: categoryFixture().id,
      brandId: brandFixture().id,
      title: "青荷通勤袋",
      subtitle: "经营商品事实",
      description: "完整 owner 详情",
      skus: [{
        skuCode: "BAG-BLACK",
        name: "黑色",
        specJson: "{}",
        salePrice: "189.00",
      }],
    };
    const posts: Array<{ key: string | null; body: string | null }> = [];
    let createAttempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      if (url.pathname.endsWith("/admin/products") && init?.method === "POST") {
        createAttempts += 1;
        posts.push({
          key: new Headers(init.headers).get("Idempotency-Key"),
          body: typeof init.body === "string" ? init.body : null,
        });
        if (createAttempts === 1) throw new TypeError("connection reset");
        return success(productDetailFixture());
      }
      if (url.pathname.includes("/by-idempotency-key/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "not observed");
      }
      if (url.pathname.endsWith("/admin/products")) {
        return success(productPage([productFixture()]));
      }
      throw new Error(`unexpected request ${url.pathname}`);
    }));

    const store = useAdminCatalogStore();
    store.synchronizeAccess(ACCESS);
    await store.createProduct(ACCESS, request);
    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCreate?.input).toEqual(request);
    const originalKey = store.pendingCreate?.commandId;
    expect(localStorage.getItem(
      `plain-journal:admin-catalog:pending-create:v1:${ACCESS.operatorId}`,
    )).toContain(originalKey);

    await store.resolvePendingCreate(ACCESS);
    expect(store.commandPhase).toBe("unknown");
    await store.retryPendingCreate(ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(store.pendingCreate).toBeNull();
    expect(store.selectedProduct?.id).toBe(productDetailFixture().id);
    expect(posts).toHaveLength(2);
    expect(posts[0]).toEqual(posts[1]);
    expect(posts[0]?.key).toBe(originalKey);
    expect(localStorage.getItem(
      `plain-journal:admin-catalog:pending-create:v1:${ACCESS.operatorId}`,
    )).toBeNull();
  });
});
