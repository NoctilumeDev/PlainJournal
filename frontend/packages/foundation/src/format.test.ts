import { describe, expect, it } from "vitest";

import { multiplyMoney, sumMoney } from "./format";

describe("money arithmetic", () => {
  it("keeps checkout line amounts exact in cents", () => {
    expect(multiplyMoney("0.10", 3)).toBe("0.30");
    expect(multiplyMoney(189, 2)).toBe("378.00");
    expect(sumMoney(["0.10", "0.20", "378.00"])).toBe("378.30");
  });

  it("rejects values that cannot be represented as cents", () => {
    expect(() => multiplyMoney("1.001", 1)).toThrow();
    expect(() => multiplyMoney("1.00", -1)).toThrow();
  });
});
