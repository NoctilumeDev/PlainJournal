import { expect, it } from "vitest";

import type { ReturnReceipt } from "@plain-journal/foundation";

import { returnReceiptStatusPresentation } from "./returnReceiptStatus";

it("translates Fulfillment states without hiding the owner boundary", () => {
  const receipt = { status: "RECEIVED" } as ReturnReceipt;
  expect(returnReceiptStatusPresentation(receipt)).toEqual({
    label: "仓库已收货",
    detail: "Fulfillment 已确认收货，仍待验收。",
    tone: "processing",
  });
});
