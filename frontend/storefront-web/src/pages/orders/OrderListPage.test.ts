import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useSessionStore } from "../../features/customer-session";
import OrderListPage from "./OrderListPage.vue";

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

function orderFixture(status: string, orderNo: string) {
  return {
    orderNo,
    status,
    totalAmount: "398.00",
    priceSnapshot: null,
    paymentDeadline: "2026-08-02T00:15:00Z",
    closeReason: null,
    deliveryAddress: {
      sourceAddressId: "2079000000000000888",
      recipientName: "Test Customer",
      phone: "+86 13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "文三路 1 号",
      postalCode: "310000",
    },
    items: [{
      lineNo: 1,
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuCode: "BAG-NATURAL-M",
      skuName: "自然色 / 中号",
      specJson: "{}",
      imageObjectKey: null,
      unitPrice: "199.00",
      quantity: 2,
      lineAmount: "398.00",
      discountAmount: "0.00",
      payableAmount: "398.00",
    }],
    version: 1,
    createdAt: "2026-08-02T00:00:00Z",
    updatedAt: "2026-08-02T00:00:01Z",
  };
}

describe("OrderListPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("renders account orders as continuous factual rows", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success({
      items: [
        orderFixture("PENDING_PAYMENT", "ORD-PENDING"),
        orderFixture("COMPLETED", "ORD-COMPLETED"),
      ],
      page: 1,
      size: 20,
      total: 2,
    })));

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

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/account", component: { template: "<div>account</div>" } },
        { path: "/products", component: { template: "<div>products</div>" } },
        {
          path: "/orders/:orderNo",
          name: "order-detail",
          component: { template: "<div>order</div>" },
        },
        { path: "/orders", component: OrderListPage },
      ],
    });
    await router.push("/orders");
    await router.isReady();

    const wrapper = mount(OrderListPage, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.find(".pj-page-container.order-list-page").exists()).toBe(true);
    expect(wrapper.findAll(".order-row")).toHaveLength(2);
    expect(wrapper.find(".order-card").exists()).toBe(false);
    expect(wrapper.text()).toContain("ORD-PENDING");
    expect(wrapper.text()).toContain("ORD-COMPLETED");
    expect(wrapper.text()).toContain("支付进行中与取消保持互斥");
    expect(wrapper.find(".order-row__status--success").text()).toBe("已完成");
  });
});
