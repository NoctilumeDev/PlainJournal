import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";

import ProductCard from "./ProductCard.vue";

describe("ProductCard", () => {
  it("uses the exact string identifier in the product route", async () => {
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

    const wrapper = mount(ProductCard, {
      global: { plugins: [router] },
      props: {
        product: {
          id: "2079000000000000001",
          title: "帆布通勤袋",
          subtitle: "能独立站立的日常包袋",
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
          coverUrl: "/images/catalog/canvas-commuter-tote.png",
        },
      },
    });

    expect(wrapper.get("a").attributes("href"))
      .toBe("/products/2079000000000000001");
    expect(wrapper.get("img").attributes()).toMatchObject({
      alt: "帆布通勤袋",
      src: "/images/catalog/canvas-commuter-tote.png",
      loading: "lazy",
      decoding: "async",
    });
    expect(wrapper.get("h3").text()).toBe("帆布通勤袋");
    expect(wrapper.text()).toContain("¥189.00");
    expect(wrapper.get("article").classes()).toContain("product-card--portrait");
  });

  it("uses the page-provided heading level without changing the product route", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{
        path: "/products/:productId",
        name: "product-detail",
        component: { template: "<div />" },
      }],
    });
    await router.push("/products/2079000000000000001");
    await router.isReady();

    const wrapper = mount(ProductCard, {
      global: { plugins: [router] },
      props: {
        headingLevel: 2,
        product: {
          id: "2079000000000000001",
          title: "帆布通勤袋",
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
        },
      },
    });

    expect(wrapper.get("h2").text()).toBe("帆布通勤袋");
    expect(wrapper.get("a").attributes("href"))
      .toBe("/products/2079000000000000001");
  });
});
