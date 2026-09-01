import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";

import type { ProductSummary } from "@plain-journal/foundation";

import ProductMasonry from "./ProductMasonry.vue";

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

describe("ProductMasonry", () => {
  it("keeps product reading order while alternating presentation rhythm", async () => {
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

    const wrapper = mount(ProductMasonry, {
      global: { plugins: [router] },
      props: {
        products: [
          product("2079000000000000001", "帆布通勤袋"),
          product("2079000000000000002", "桌面收纳盒"),
          product("2079000000000000003", "陶杯"),
        ],
      },
    });

    expect(wrapper.findAll("article")).toHaveLength(3);
    expect(wrapper.findAll("h3").map((heading) => heading.text())).toEqual([
      "帆布通勤袋",
      "桌面收纳盒",
      "陶杯",
    ]);
    expect(wrapper.findAll("article").map((card) => card.classes())).toEqual([
      expect.arrayContaining(["product-card--landscape"]),
      expect.arrayContaining(["product-card--square"]),
      expect.arrayContaining(["product-card--square"]),
    ]);
  });
});
