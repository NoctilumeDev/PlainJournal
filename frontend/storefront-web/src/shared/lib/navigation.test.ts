import { describe, expect, it } from "vitest";

import { safeReturnTo } from "./navigation";

describe("safeReturnTo", () => {
  it("accepts local application paths", () => {
    expect(safeReturnTo("/bag?source=login")).toBe("/bag?source=login");
  });

  it("rejects protocol-relative and non-path redirects", () => {
    expect(safeReturnTo("//example.com")).toBe("/account");
    expect(safeReturnTo("https://example.com")).toBe("/account");
    expect(safeReturnTo("/\\example.com")).toBe("/account");
  });
});
