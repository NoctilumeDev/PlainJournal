import { describe, expect, it } from "vitest";

import {
  formatMoney,
  multiplyMoney,
  parseSpecification,
  sumMoney,
} from "./format";

describe("money arithmetic", () => {
  it("keeps checkout line amounts exact in cents", () => {
    expect(multiplyMoney("0.10", 3)).toBe("0.30");
    expect(multiplyMoney(189, 2)).toBe("378.00");
    expect(sumMoney(["0.10", "0.20", "378.00"])).toBe("378.30");
  });

  it("rejects values that cannot be represented as cents", () => {
    expect(() => multiplyMoney("1.001", 1)).toThrow();
    expect(() => multiplyMoney("1.00", -1)).toThrow();
    expect(() => multiplyMoney("1.00", Number.MAX_SAFE_INTEGER + 1)).toThrow();
    expect(() => sumMoney(["-1.00"])).toThrow();
  });

  it("formats display values without treating invalid input as money", () => {
    expect(formatMoney("189")).toContain("189.00");
    expect(formatMoney(0)).toContain("0.00");
    expect(formatMoney(null)).toBe("—");
    expect(formatMoney("not-money")).toBe("—");
  });

  it("keeps only scalar product specifications", () => {
    expect(parseSpecification(JSON.stringify({
      color: "青荷",
      weight: 320,
      washable: true,
      nested: { ignored: true },
      list: ["ignored"],
    }))).toEqual([
      { label: "color", value: "青荷" },
      { label: "weight", value: "320" },
      { label: "washable", value: "true" },
    ]);
    expect(parseSpecification("[]")).toEqual([]);
    expect(parseSpecification("{broken")).toEqual([]);
  });
});
