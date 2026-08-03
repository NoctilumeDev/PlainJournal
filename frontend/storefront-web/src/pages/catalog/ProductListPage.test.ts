import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import ProductListPage from "./ProductListPage.vue";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-02T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const categories = [
  {
    id: "2079000000000000101",
    parentId: null,
    name: "随身用品",
    slug: "carry",
    sortOrder: 1,
  },
  {
    id: "2079000000000002101",
    parentId: null,
    name: "书写纸品",
    slug: "writing",
    sortOrder: 2,
  },
];

const product = {
  id: "2079000000000000001",
  title: "帆布通勤袋",
  subtitle: "轻量、耐用，保留材料本来的质感",
  category: categories[0],
  brand: {
    id: "2079000000000000201",
    name: "素简记",
    slug: "plain-journal",
  },
  minimumPrice: "189.00",
  coverUrl: "/images/catalog/canvas-commuter-tote.png",
};

describe("ProductListPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads category and page from the URL without coercing product identities", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      requests.push(`${url.pathname}?${url.searchParams.toString()}`);
      if (url.pathname === "/api/v1/catalog/categories") {
        return success(categories);
      }
      if (url.pathname === "/api/v1/catalog/products") {
        return success({
          items: [product],
          page: 2,
          size: 12,
          total: 25,
        });
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: "/products",
          name: "products",
          component: ProductListPage,
        },
        {
          path: "/products/:productId",
          name: "product-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/products?category=carry&page=2");
    await router.isReady();

    const wrapper = mount(ProductListPage, {
      global: { plugins: [router] },
    });
    await flushPromises();

    expect(wrapper.get("h1").text()).toBe("随身用品");
    expect(wrapper.get(".category-rail a.is-active").text()).toBe("随身用品");
    expect(wrapper.get(".category-rail a.is-active").attributes("aria-current"))
      .toBe("page");
    expect(wrapper.get(".product-card__link").attributes("href"))
      .toBe("/products/2079000000000000001");
    expect(wrapper.get("h2").text()).toBe("帆布通勤袋");
    expect(wrapper.get('a[rel="prev"]').attributes("href"))
      .toBe("/products?category=carry");
    expect(wrapper.get('a[rel="next"]').attributes("href"))
      .toBe("/products?category=carry&page=3");
    expect(wrapper.get(".category-rail").text()).not.toContain("第 2 页");
    expect(requests).toContain(
      "/api/v1/catalog/products?page=2&size=12&categoryId=2079000000000000101",
    );
  });

  it("normalizes an invalid page to the first API page", async () => {
    const productQueries: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/catalog/categories") {
        return success(categories);
      }
      if (url.pathname === "/api/v1/catalog/products") {
        productQueries.push(url.searchParams.toString());
        return success({ items: [product], page: 1, size: 12, total: 1 });
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/products", name: "products", component: ProductListPage },
        {
          path: "/products/:productId",
          name: "product-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/products?page=not-a-page");
    await router.isReady();

    mount(ProductListPage, { global: { plugins: [router] } });
    await flushPromises();

    expect(productQueries).toEqual(["page=1&size=12"]);
  });
});
