import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useSessionStore } from "../features/customer-session";
import AfterSaleListView from "./AfterSaleListView.vue";

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

function afterSaleFixture() {
  return {
    afterSaleNo: "AS2079000000000003001",
    orderNo: "ORD2079000000000003002",
    userId: "2079000000000000999",
    afterSaleType: "RETURN_REFUND",
    status: "REFUND_FAILED",
    reason: "商品到货后存在明确破损",
    reviewReason: "符合整单退货退款条件",
    refundAmount: "378.00",
    returnReceiptNo: "RET2079000000000003003",
    refundNo: "REF2079000000000003004",
    items: [],
    version: 4,
    createdAt: "2026-08-02T00:00:00Z",
    updatedAt: "2026-08-02T00:04:00Z",
    approvedAt: "2026-08-02T00:01:00Z",
    completedAt: null,
  };
}

describe("AfterSaleListView", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders continuous facts and keeps governance attention distinct from success", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async () => success([afterSaleFixture()])));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        { path: "/account", component: { template: "<div />" } },
        { path: "/orders", component: { template: "<div />" } },
        {
          path: "/after-sales/:afterSaleNo",
          name: "after-sale-detail",
          component: { template: "<div />" },
        },
      ],
    });
    await router.push("/");
    await router.isReady();

    const wrapper = mount(AfterSaleListView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.findAll(".after-sale-row")).toHaveLength(1);
    expect(wrapper.find(".order-card").exists()).toBe(false);
    expect(wrapper.find(".after-sale-row__status--attention").exists()).toBe(true);
    expect(wrapper.find(".after-sale-row__status--success").exists()).toBe(false);
    expect(wrapper.text()).toContain("平台财务治理");
    expect(wrapper.get("a[aria-label*='ORD2079000000000003002']").text())
      .toBe("查看售后详情");
  });
});
