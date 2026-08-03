import { describe, expect, it } from "vitest";

import type { AfterSale } from "@plain-journal/foundation";

import { afterSaleProgress, afterSaleStatusPresentation } from "./afterSaleStatus";

function fixture(status: string): AfterSale {
  return {
    afterSaleNo: "AS-1",
    orderNo: "ORD-1",
    userId: "2079000000000000999",
    afterSaleType: "RETURN_REFUND",
    status,
    reason: "破损",
    reviewReason: null,
    refundAmount: "189.00",
    returnReceiptNo: null,
    refundNo: null,
    items: [],
    version: 0,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    approvedAt: null,
    completedAt: status === "COMPLETED" ? "2026-08-01T01:00:00Z" : null,
  };
}

describe("after-sale presentation", () => {
  it("names the owner and refuses to invent a completion time", () => {
    const copy = afterSaleStatusPresentation(fixture("REFUNDING"));
    expect(copy.owner).toContain("Payment");
    expect(copy.timing).toContain("不提供虚假预计时间");
  });

  it("marks only authoritative completed stages as completed", () => {
    expect(afterSaleProgress(fixture("WAIT_RETURN")).map((stage) => stage.state))
      .toEqual(["completed", "current", "upcoming", "upcoming"]);
    expect(afterSaleProgress(fixture("COMPLETED")).every((stage) => stage.state === "completed"))
      .toBe(true);
  });
});
