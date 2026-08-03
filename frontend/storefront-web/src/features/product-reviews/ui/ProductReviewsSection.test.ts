import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import ProductReviewsSection from "./ProductReviewsSection.vue";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-01T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const review = {
  id: "5001",
  productId: "2001",
  skuId: "3001",
  skuName: "自然色 / 中号",
  specJson: "{}",
  rating: 5,
  content: "通勤使用一周后仍然稳定。",
  anonymous: false,
  authorLabel: "已购顾客",
  status: "PUBLISHED",
  likeCount: 0,
  likedByViewer: false,
  reply: null,
  createdAt: "2026-08-01T00:00:00Z",
};

describe("ProductReviewsSection", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    setActivePinia(createPinia());
  });

  it("shows Catalog facts and updates one verified like response", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.endsWith("/review-summary")) {
        return success({
          productId: "2001",
          reviewCount: 1,
          averageRating: 5,
          rating1Count: 0,
          rating2Count: 0,
          rating3Count: 0,
          rating4Count: 0,
          rating5Count: 1,
        });
      }
      if (url.pathname.endsWith("/reviews")) {
        return success({ items: [review], page: 1, size: 50, total: 1 });
      }
      if (url.pathname.endsWith("/likes")) {
        return success({ ...review, likedByViewer: true, likeCount: 1 });
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/products/:productId", component: { template: "<div />" } },
        { path: "/login", name: "login", component: { template: "<div />" } },
      ],
    });
    await router.push("/products/2001");
    await router.isReady();
    const pinia = createPinia();
    setActivePinia(pinia);
    const wrapper = mount(ProductReviewsSection, {
      props: {
        access: {
          authenticated: true,
          ownerId: "1001",
          accessToken: "token-a",
        },
        productId: "2001",
      },
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("通勤使用一周后仍然稳定");
    expect(wrapper.text()).toContain("5.0");
    const likeButton = wrapper.findAll("button").find((button) =>
      button.text().includes("有用 · 0"));
    expect(likeButton).toBeDefined();
    await likeButton?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("取消有用 · 1");
  });
});
