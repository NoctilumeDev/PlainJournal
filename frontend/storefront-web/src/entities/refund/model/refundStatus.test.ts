import { expect, it } from "vitest";

import type { Refund } from "@plain-journal/foundation";

import { refundStatusPresentation } from "./refundStatus";

it("treats NEEDS_ATTENTION as governance work rather than success", () => {
  const refund = { status: "PROCESSING", requestStatus: "NEEDS_ATTENTION" } as Refund;
  expect(refundStatusPresentation(refund).tone).toBe("attention");
  expect(refundStatusPresentation(refund).label).toBe("需要处理");
});
