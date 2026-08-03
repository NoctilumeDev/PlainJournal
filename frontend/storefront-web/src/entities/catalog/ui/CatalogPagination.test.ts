import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";

import CatalogPagination from "./CatalogPagination.vue";

describe("CatalogPagination", () => {
  it("renders URL-backed previous and next facts", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{
        path: "/products",
        name: "products",
        component: { template: "<div />" },
      }],
    });
    await router.push("/products?category=carry&page=2");
    await router.isReady();

    const wrapper = mount(CatalogPagination, {
      global: { plugins: [router] },
      props: {
        currentPage: 2,
        totalPages: 3,
        toPage: (page) => ({
          name: "products",
          query: page === 1
            ? { category: "carry" }
            : { category: "carry", page: String(page) },
        }),
      },
    });

    expect(wrapper.get('[aria-label="商品分页"]').text()).toContain("第 2 页");
    expect(wrapper.get('a[rel="prev"]').attributes("href"))
      .toBe("/products?category=carry");
    expect(wrapper.get('a[rel="next"]').attributes("href"))
      .toBe("/products?category=carry&page=3");
  });

  it("does not add navigation noise for a single page", () => {
    const wrapper = mount(CatalogPagination, {
      props: {
        currentPage: 1,
        totalPages: 1,
        toPage: () => "/products",
      },
    });

    expect(wrapper.find("nav").exists()).toBe(false);
  });
});
