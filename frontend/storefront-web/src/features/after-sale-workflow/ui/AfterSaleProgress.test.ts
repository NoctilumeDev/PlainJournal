import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import type { AfterSale } from "@plain-journal/foundation";

import AfterSaleProgress from "./AfterSaleProgress.vue";

function fixture(status: string): AfterSale {
  return {
    afterSaleNo: "AS-1",
    orderNo: "ORD-1",
    userId: "2079000000000000999",
    afterSaleType: "RETURN_REFUND",
    status,
    reason: "商品破损",
    reviewReason: "符合整单退货条件",
    refundAmount: "378.00",
    returnReceiptNo: "RET-1",
    refundNo: null,
    items: [],
    version: 1,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    approvedAt: "2026-08-01T00:00:00Z",
    completedAt: null,
  };
}

describe("AfterSaleProgress", () => {
  it("shows the current owner, next action and non-fabricated timing", () => {
    const wrapper = mount(AfterSaleProgress, {
      props: { afterSale: fixture("WAIT_RETURN") },
    });

    expect(wrapper.text()).toContain("当前处理方");
    expect(wrapper.text()).toContain("顾客");
    expect(wrapper.text()).toContain("提交真实寄回信息");
    expect(wrapper.text()).toContain("以承运商运输与仓库收货事实为准");
    expect(wrapper.get('[aria-current="step"]').text()).toContain("寄回与验收");
  });
});
