import { describe, expect, it, vi } from "vitest";

import { returnToPreviousOr, safeReturnTo } from "./navigation";

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

describe("returnToPreviousOr", () => {
  it("uses application history when the current entry records a predecessor", async () => {
    const router = {
      options: { history: { state: { back: "/search?q=paper" } } },
      back: vi.fn(),
      replace: vi.fn(),
    };

    await expect(returnToPreviousOr(router as never, "/products"))
      .resolves.toBe("history");
    expect(router.back).toHaveBeenCalledOnce();
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("uses the declared fallback for a direct deep link", async () => {
    const router = {
      options: { history: { state: { back: null } } },
      back: vi.fn(),
      replace: vi.fn().mockResolvedValue(undefined),
    };

    await expect(returnToPreviousOr(router as never, { name: "products" }))
      .resolves.toBe("fallback");
    expect(router.replace).toHaveBeenCalledWith({ name: "products" });
    expect(router.back).not.toHaveBeenCalled();
  });

  it("uses the fallback when the caller rejects a stale or terminal predecessor", async () => {
    const router = {
      options: { history: { state: { back: "/checkout?source=bag#review" } } },
      back: vi.fn(),
      replace: vi.fn().mockResolvedValue(undefined),
    };

    await expect(returnToPreviousOr(
      router as never,
      { name: "orders" },
      (path) => path.split(/[?#]/, 1)[0] !== "/checkout",
    )).resolves.toBe("fallback");
    expect(router.replace).toHaveBeenCalledWith({ name: "orders" });
    expect(router.back).not.toHaveBeenCalled();
  });
});
