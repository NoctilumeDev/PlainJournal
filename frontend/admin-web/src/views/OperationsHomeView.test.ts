import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import OperationsHomeView from "./OperationsHomeView.vue";
import { useStaffSessionStore } from "../stores/session";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function dashboardFixture() {
  return {
    from: "2026-07-05",
    to: "2026-08-03",
    totals: {
      createdOrderCount: 12,
      createdOrderAmount: 2268,
      paymentCount: 10,
      paymentAmount: 1890,
      completedOrderCount: 8,
      completedOrderAmount: 1512,
      closedOrderCount: 2,
      afterSaleCount: 1,
      afterSaleAmount: 189,
      refundCount: 1,
      refundAmount: 189,
      uniqueCustomers: 9,
    },
    daily: [{
      businessDate: "2026-08-03",
      createdOrderCount: 12,
      createdOrderAmount: 2268,
      paymentCount: 10,
      paymentAmount: 1890,
      completedOrderCount: 8,
      completedOrderAmount: 1512,
      closedOrderCount: 2,
      afterSaleCount: 1,
      afterSaleAmount: 189,
      refundCount: 1,
      refundAmount: 189,
      updatedAt: "2026-08-03T00:00:00Z",
    }],
    topProducts: [{
      productId: "2088000000000000101",
      productTitle: "青荷帆布通勤袋",
      completedOrderCount: 6,
      unitsSold: 8,
      netRevenue: 1512,
      revenueCoveredOrderCount: 6,
    }],
    freshness: {
      sourceEventCount: 34,
      lastConsumedAt: "2026-08-03T00:00:00Z",
      generatedAt: "2026-08-03T00:00:05Z",
    },
  };
}

function mountWorkspace() {
  return mount(OperationsHomeView, {
    global: {
      stubs: {
        RouterLink: {
          props: ["to"],
          template: "<a :href=\"String(to)\"><slot /></a>",
        },
      },
    },
  });
}

describe("operations workspace home", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-03T08:00:00+08:00"));
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps warehouse staff inside inventory and fulfillment boundaries", async () => {
    const session = useStaffSessionStore();
    session.profile = {
      id: "2088000000000000002",
      email: "warehouse@example.com",
      displayName: "Warehouse",
      status: "ACTIVE",
      roles: ["WAREHOUSE"],
    };
    session.accessToken = "warehouse-token";
    session.initialized = true;

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("库存");
    expect(wrapper.text()).toContain("履约与退货");
    expect(wrapper.text()).not.toContain("商品目录");
    expect(wrapper.text()).toContain("运营统计按角色隔离");
  });

  it("loads the bounded analytics projection for an administrator", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(success(dashboardFixture())));
    const session = useStaffSessionStore();
    session.profile = {
      id: "2088000000000000003",
      email: "admin@example.com",
      displayName: "Admin",
      status: "ACTIVE",
      roles: ["ADMIN"],
    };
    session.accessToken = "admin-token";
    session.initialized = true;

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("8 个入口");
    expect(wrapper.text()).toContain("创建订单");
    expect(wrapper.text()).toContain("青荷帆布通勤袋");
    expect(wrapper.text()).toContain("来源事件");
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
