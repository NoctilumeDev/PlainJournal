import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import ProductDetailView from "./ProductDetailView.vue";

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
  description: "适合通勤与短途使用的克制日常用品。",
  status: "ACTIVE",
  version: 1,
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
  skus: [{
    id: "2079000000000000011",
    skuCode: "BAG-NATURAL-M",
    name: "自然色 / 中号",
    specJson: "{\"颜色\":\"自然色\",\"尺寸\":\"中号\"}",
    salePrice: "189.00",
    marketPrice: "219.00",
    status: "ACTIVE",
    version: 0,
  }],
  media: [
    {
      id: "2079000000000000301",
      skuId: null,
      objectKey: "demo/catalog/canvas-commuter-tote.png",
      mimeType: "image/png",
      sizeBytes: 1999183,
      sortOrder: 0,
      url: "/images/catalog/canvas-commuter-tote.png",
    },
    {
      id: "2079000000000000302",
      skuId: null,
      objectKey: "demo/catalog/canvas-commuter-tote-detail.png",
      mimeType: "image/png",
      sizeBytes: 1999183,
      sortOrder: 1,
      url: "/images/catalog/canvas-commuter-tote-detail.png",
    },
  ],
};

describe("ProductDetailView image URL state", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("restores the selected image from the URL and changes it without losing route facts", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === `/api/v1/catalog/products/${product.id}`) {
        return success(product);
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: "/products/:productId",
          name: "product-detail",
          component: ProductDetailView,
        },
        { path: "/products", name: "products", component: { template: "<div />" } },
        { path: "/bag", component: { template: "<div />" } },
      ],
    });
    await router.push(`/products/${product.id}?image=2&from=search`);
    await router.isReady();

    const wrapper = mount(ProductDetailView, {
      global: {
        plugins: [pinia, router],
        stubs: {
          ProductReviewsSection: { template: "<section>reviews</section>" },
        },
      },
    });
    await flushPromises();

    expect(wrapper.get(".product-media__main img").attributes()).toMatchObject({
      src: "/images/catalog/canvas-commuter-tote-detail.png",
      alt: "帆布通勤袋，图片 2",
      decoding: "async",
    });
    expect(wrapper.get('[aria-label="查看第 2 张图片"]').attributes("aria-current"))
      .toBe("true");

    await wrapper.get('[aria-label="查看第 1 张图片"]').trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.query).toEqual({
      image: "1",
      from: "search",
    });
    expect(wrapper.get(".product-media__main img").attributes("src"))
      .toBe("/images/catalog/canvas-commuter-tote.png");
  });
});
