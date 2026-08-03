import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import OrderReviewSection from "./OrderReviewSection.vue";

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

describe("OrderReviewSection", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    setActivePinia(createPinia());
  });

  it("renders only the current order eligibility and the stable submission action", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([{
      id: "4001",
      orderNo: "ORD-1",
      lineNo: 1,
      productId: "2001",
      skuId: "3001",
      productTitle: "帆布通勤袋",
      skuCode: "BAG-1",
      skuName: "自然色 / 中号",
      specJson: "{}",
      imageObjectKey: null,
      quantity: 1,
      status: "ELIGIBLE",
      reviewId: null,
      completedAt: "2026-08-01T00:00:00Z",
    }])));
    const pinia = createPinia();
    setActivePinia(pinia);
    const wrapper = mount(OrderReviewSection, {
      props: {
        access: {
          authenticated: true,
          ownerId: "1001",
          accessToken: "token-a",
        },
        orderNo: "ORD-1",
      },
      global: { plugins: [pinia] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("完成订单后再评价");
    expect(wrapper.text()).toContain("帆布通勤袋");
    expect(wrapper.text()).toContain("每个不可变订单行只有一次评价资格");
    expect(wrapper.get('button[type="submit"]').text()).toBe("提交评价");
  });
});
