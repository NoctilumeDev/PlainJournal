import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";

import type { ProductSummary } from "@plain-journal/foundation";

import ProductGrid from "./ProductGrid.vue";

function product(id: string, title: string): ProductSummary {
  return {
    id,
    title,
    subtitle: null,
    category: {
      id: "2079000000000000101",
      parentId: null,
      name: "随行",
      slug: "carry",
      sortOrder: 10,
    },
    brand: {
      id: "2079000000000000201",
      name: "素简记选物",
      slug: "plain-journal",
    },
    minimumPrice: "189.00",
    coverUrl: null,
  };
}

describe("ProductGrid", () => {
  it("renders every catalog identity without numeric coercion", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        {
          path: "/products/:productId",
          name: "product-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/");
    await router.isReady();

    const wrapper = mount(ProductGrid, {
      global: { plugins: [router] },
      props: {
        headingLevel: 2,
        products: [
          product("2079000000000000001", "帆布通勤袋"),
          product("2079000000000000002", "桌面收纳盒"),
        ],
      },
    });

    expect(wrapper.findAll("article")).toHaveLength(2);
    expect(wrapper.findAll("h2")).toHaveLength(2);
    expect(wrapper.findAll("a").map((link) => link.attributes("href"))).toEqual([
      "/products/2079000000000000001",
      "/products/2079000000000000002",
    ]);
  });
});
