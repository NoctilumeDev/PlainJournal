import { describe, expect, it } from "vitest";

import {
  pageCount,
  pageFromQuery,
  queryWithPage,
} from "./pagination";

describe("URL pagination helpers", () => {
  it("accepts only positive integer page facts", () => {
    expect(pageFromQuery("2")).toBe(2);
    expect(pageFromQuery(["3", "4"])).toBe(3);
    expect(pageFromQuery(undefined)).toBe(1);
    expect(pageFromQuery("0")).toBe(1);
    expect(pageFromQuery("-1")).toBe(1);
    expect(pageFromQuery("2.5")).toBe(1);
    expect(pageFromQuery("not-a-page")).toBe(1);
  });

  it("calculates page counts without exposing zero or invalid pages", () => {
    expect(pageCount(0, 12)).toBe(1);
    expect(pageCount(1, 12)).toBe(1);
    expect(pageCount(13, 12)).toBe(2);
    expect(pageCount(Number.NaN, 12)).toBe(1);
    expect(pageCount(13, 0)).toBe(1);
  });

  it("preserves other query facts and omits the canonical first page", () => {
    expect(queryWithPage({ category: "carry", page: "3" }, 1)).toEqual({
      category: "carry",
    });
    expect(queryWithPage({ q: "通勤" }, 2)).toEqual({
      q: "通勤",
      page: "2",
    });
  });
});
