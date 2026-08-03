import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useBagStore } from "./guestBag";

describe("guest bag", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it("merges the same sku without creating a duplicate row", () => {
    const bag = useBagStore();
    const item = {
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    };

    bag.addItem(item);
    bag.addItem(item);

    expect(bag.items).toHaveLength(1);
    expect(bag.itemCount).toBe(2);
    expect(bag.subtotal).toBe(378);
  });

  it("supports immediate removal and explicit undo", () => {
    const bag = useBagStore();
    const item = {
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    };
    bag.addItem(item);

    const removed = bag.removeItem(item.skuId);
    expect(bag.items).toHaveLength(0);

    if (removed) {
      bag.restoreItem(removed);
    }
    expect(bag.items).toEqual([item]);
  });

  it("keeps one stable merge request until the server result is confirmed", () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 2,
      coverUrl: null,
    });

    const first = bag.prepareMerge("2079000000000000999");
    bag.addItem({
      ...bag.items[0]!,
      quantity: 1,
    });
    const retry = bag.prepareMerge("2079000000000000999");

    expect(retry).toEqual(first);
    expect(retry?.items[0]?.quantity).toBe(2);

    expect(bag.completeMerge(first!.key)).toBe(true);
    expect(bag.items).toHaveLength(1);
    expect(bag.items[0]?.quantity).toBe(1);
    expect(bag.pendingMerge).toBeNull();
  });

  it("does not replay an unknown merge into another account", () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    });
    bag.prepareMerge("2079000000000000999");

    expect(() => bag.prepareMerge("2079000000000000888"))
      .toThrow("另一账户");
  });

  it("keeps the last valid quantity when a number input produces a non-finite value", () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 2,
      coverUrl: null,
    });

    bag.updateQuantity("2079000000000000011", Number.NaN);
    bag.updateQuantity("2079000000000000011", Number.POSITIVE_INFINITY);

    expect(bag.items[0]?.quantity).toBe(2);
    expect(JSON.parse(String(localStorage.getItem("plain-journal:guest-bag:v1"))))
      .toMatchObject([{ quantity: 2 }]);
  });

  it("repairs malformed persisted rows instead of sending them to Trade", () => {
    localStorage.setItem("plain-journal:guest-bag:v1", JSON.stringify([
      {
        productId: "2079000000000000001",
        skuId: "2079000000000000011",
        productTitle: "帆布通勤袋",
        skuName: "自然色 / 中号",
        unitPrice: "189.00",
        quantity: 2,
        coverUrl: null,
      },
      {
        productId: "2079000000000000001",
        skuId: "2079000000000000011",
        productTitle: "帆布通勤袋",
        skuName: "自然色 / 中号",
        unitPrice: "189.00",
        quantity: 1,
        coverUrl: null,
      },
      {
        productId: "not-a-business-id",
        skuId: "2079000000000000099",
        quantity: null,
      },
    ]));

    const bag = useBagStore();

    expect(bag.items).toHaveLength(1);
    expect(bag.items[0]?.quantity).toBe(3);
    expect(bag.prepareMerge("2079000000000000999")?.items).toEqual([{
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      quantity: 3,
    }]);
  });

  it("discards a malformed persisted pending request before owner replay", () => {
    localStorage.setItem("plain-journal:guest-bag-merge:v1", JSON.stringify({
      key: "guest-merge:unsafe",
      userId: "2079000000000000999",
      items: [{
        productId: "2079000000000000001",
        skuId: "2079000000000000011",
        quantity: null,
      }],
    }));

    const bag = useBagStore();

    expect(bag.pendingMerge).toBeNull();
    expect(localStorage.getItem("plain-journal:guest-bag-merge:v1")).toBeNull();
  });
});
