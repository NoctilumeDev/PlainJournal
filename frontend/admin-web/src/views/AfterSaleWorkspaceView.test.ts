import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import type { AfterSale } from "@plain-journal/foundation";

import AfterSaleWorkspaceView from "./AfterSaleWorkspaceView.vue";
import { useStaffSessionStore } from "../stores/session";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-09-01T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function afterSaleFixture(
  overrides: Partial<AfterSale> = {},
): AfterSale {
  return {
    afterSaleNo: "AS2088000000000000101",
    orderNo: "ORD2088000000000000102",
    userId: "2088000000000000103",
    afterSaleType: "WHOLE_RETURN_REFUND",
    status: "APPLIED",
    reason: "整单商品存在明确破损",
    reviewReason: null,
    refundAmount: "378.00",
    returnReceiptNo: null,
    refundNo: null,
    items: [{
      lineNo: 1,
      skuId: "2088000000000000104",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      quantity: 2,
      lineAmount: "398.00",
      discountAmount: "20.00",
      refundableAmount: "378.00",
    }],
    version: 0,
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    approvedAt: null,
    completedAt: null,
    ...overrides,
  };
}

function mountWorkspace() {
  const session = useStaffSessionStore();
  session.profile = {
    id: "2088000000000000001",
    email: "admin@example.com",
    displayName: "Admin",
    status: "ACTIVE",
    roles: ["ADMIN"],
  };
  session.accessToken = "admin-token";
  session.initialized = true;
  return mount(AfterSaleWorkspaceView);
}

describe("after-sale split workbench", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps task filters, queue selection, and facts in separate regions", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(success([
      afterSaleFixture(),
      afterSaleFixture({
        afterSaleNo: "AS2088000000000000202",
        orderNo: "ORD2088000000000000203",
        status: "WAIT_RETURN",
        reason: "尺寸不合适",
      }),
    ])));

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.find(".split-workbench__rail").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__queue").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__detail").exists()).toBe(true);
    expect(wrapper.findAll(".after-sale-queue__list button")).toHaveLength(2);
    expect(wrapper.text()).toContain("不可变退款快照");
    expect(wrapper.text()).toContain("帆布通勤袋");

    await wrapper.findAll(".after-sale-queue__list button")[1]!.trigger("click");

    expect(wrapper.text()).toContain("AS2088000000000000202");
    expect(wrapper.text()).toContain("等待寄回");
    expect(wrapper.find(".after-sale-review").exists()).toBe(false);
  });

  it("offers review commands only while the authoritative status is APPLIED", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(success([
      afterSaleFixture(),
    ])));

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.find(".after-sale-review").exists()).toBe(true);
    expect(wrapper.text()).toContain("批准退款");
    expect(wrapper.text()).toContain("拒绝申请");
    expect(wrapper.text()).toContain("当前处理边界");
  });
});
