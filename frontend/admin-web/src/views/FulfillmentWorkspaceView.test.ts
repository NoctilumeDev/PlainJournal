import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import type { Fulfillment, ReturnReceipt } from "@plain-journal/foundation";

import FulfillmentWorkspaceView from "./FulfillmentWorkspaceView.vue";
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

function fulfillmentFixture(): Fulfillment {
  return {
    fulfillmentNo: "FUL2085000000000000003",
    orderNo: "ORD2085000000000000005",
    userId: "2085000000000000006",
    deliveryAddress: {
      sourceAddressId: "2085000000000000007",
      recipientName: "仓库验收顾客",
      phone: "13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "测试路 1 号",
      postalCode: "310000",
    },
    status: "PICKING",
    carrier: null,
    trackingNo: null,
    history: [],
    traces: [],
    version: 2,
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    pickedAt: "2026-09-01T00:00:00Z",
    packedAt: null,
    shippedAt: null,
    signedAt: null,
  };
}

function returnFixture(): ReturnReceipt {
  return {
    returnReceiptNo: "RET2085000000000000004",
    afterSaleNo: "AS2085000000000000008",
    orderNo: "ORD2085000000000000005",
    userId: "2085000000000000006",
    warehouseId: "2085000000000000009",
    reservationNo: "RES2085000000000000010",
    status: "RETURNING",
    refundAmount: "189.00",
    carrier: "PLAIN_EXPRESS",
    trackingNo: "RETURN-V642",
    inspectionRemark: null,
    items: [{
      lineNo: 1,
      skuId: "2085000000000000011",
      quantity: 1,
      refundableAmount: "189.00",
    }],
    version: 1,
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    shippedAt: "2026-09-01T00:00:00Z",
    receivedAt: null,
    inspectedAt: null,
  };
}

function mountWorkspace() {
  const session = useStaffSessionStore();
  session.profile = {
    id: "2085000000000000001",
    email: "warehouse@example.com",
    displayName: "Warehouse",
    status: "ACTIVE",
    roles: ["WAREHOUSE"],
  };
  session.accessToken = "warehouse-token";
  session.initialized = true;
  return mount(FulfillmentWorkspaceView);
}

describe("fulfillment split workbench", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps forward, reverse, and geo work in one layered workspace", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success([fulfillmentFixture()]))
      .mockResolvedValueOnce(success([returnFixture()])));

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.find(".split-workbench__rail").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__queue").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__detail").exists()).toBe(true);
    expect(wrapper.findAll(".fulfillment-queue__list button")).toHaveLength(1);
    expect(wrapper.text()).toContain("履约与收件事实");
    expect(wrapper.text()).toContain("确认打包");
    expect(wrapper.find(".pj-surface").exists()).toBe(false);

    const modeButtons = wrapper.findAll(".fulfillment-mode-nav button");
    await modeButtons[1]!.trigger("click");
    expect(wrapper.text()).toContain("退货与仓库事实");
    expect(wrapper.text()).toContain("确认仓库收货");

    await modeButtons[2]!.trigger("click");
    expect(wrapper.text()).toContain("查询范围内位置");
    expect(wrapper.text()).toContain("投影边界");
  });
});
