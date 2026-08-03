import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import SearchPage from "./SearchPage.vue";

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

const product = {
  id: "2079000000000000001",
  title: "帆布通勤袋",
  subtitle: "轻量、耐用，保留材料本来的质感",
  category: {
    id: "2079000000000000101",
    parentId: null,
    name: "随身用品",
    slug: "carry",
    sortOrder: 1,
  },
  brand: {
    id: "2079000000000000201",
    name: "素简记",
    slug: "plain-journal",
  },
  minimumPrice: "189.00",
  coverUrl: "/images/catalog/canvas-commuter-tote.png",
};

describe("SearchPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("preserves query pagination and exposes the authoritative fallback source", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      requests.push(`${url.pathname}?${url.searchParams.toString()}`);
      if (url.pathname === "/api/v1/catalog/search/products") {
        return success({
          items: [product],
          page: Number(url.searchParams.get("page")),
          size: 12,
          matchedTotal: 25,
          source: "MYSQL_FALLBACK",
          degraded: true,
        });
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/search", name: "search", component: SearchPage },
        { path: "/products", name: "products", component: { template: "<div />" } },
        {
          path: "/products/:productId",
          name: "product-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/search?q=通勤&page=2");
    await router.isReady();

    const wrapper = mount(SearchPage, {
      global: { plugins: [router] },
    });
    await flushPromises();

    expect(wrapper.get<HTMLInputElement>("#site-search").element.value).toBe("通勤");
    expect(wrapper.text()).toContain("查找范围暂时收窄");
    expect(wrapper.get(".search-degraded").attributes("data-search-source"))
      .toBe("MYSQL_FALLBACK");
    expect(wrapper.text()).toContain("商品事实库的基础匹配");
    expect(wrapper.get('a[rel="prev"]').attributes("href"))
      .toBe("/search?q=%E9%80%9A%E5%8B%A4");
    expect(wrapper.get('a[rel="next"]').attributes("href"))
      .toBe("/search?q=%E9%80%9A%E5%8B%A4&page=3");
    expect(requests).toContain(
      "/api/v1/catalog/search/products?q=%E9%80%9A%E5%8B%A4&page=2&size=12",
    );

    await wrapper.get<HTMLInputElement>("#site-search").setValue("书写");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(router.currentRoute.value.query).toEqual({ q: "书写" });
    expect(requests).toContain(
      "/api/v1/catalog/search/products?q=%E4%B9%A6%E5%86%99&page=1&size=12",
    );
  });

  it("does not fabricate a search request before a query exists", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/search", name: "search", component: SearchPage },
        { path: "/products", name: "products", component: { template: "<div />" } },
        {
          path: "/products/:productId",
          name: "product-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/search");
    await router.isReady();

    const wrapper = mount(SearchPage, {
      global: { plugins: [router] },
    });
    await flushPromises();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("从用途开始");
    expect(wrapper.find(".search-results").exists()).toBe(false);
  });
});
