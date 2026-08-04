import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  secureRandomUUID,
  type BusinessId,
  type GuestBagMergeItem,
} from "@plain-journal/foundation";

export interface GuestBagItem {
  productId: BusinessId;
  skuId: BusinessId;
  productTitle: string;
  skuName: string;
  unitPrice: string;
  quantity: number;
  coverUrl: string | null;
}

const STORAGE_KEY = "plain-journal:guest-bag:v1";
const PENDING_MERGE_KEY = "plain-journal:guest-bag-merge:v1";
const MAX_GUEST_QUANTITY = 999;
const MAX_GUEST_ITEMS = 100;
const BUSINESS_ID_PATTERN = /^\d+$/u;
const MERGE_KEY_PATTERN = /^[A-Za-z0-9._:-]{8,64}$/u;

export interface PendingGuestBagMerge {
  key: string;
  userId: BusinessId;
  items: GuestBagMergeItem[];
}

export class GuestBagMergeOwnershipError extends Error {
  constructor() {
    super("这个设备上有一笔属于另一账户且结果尚未确认的购物袋合并。");
    this.name = "GuestBagMergeOwnershipError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === "object");
}

function normalizeQuantity(value: unknown, fallback: number | null = null): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return fallback;
  }
  return Math.max(1, Math.min(MAX_GUEST_QUANTITY, Math.trunc(value)));
}

function normalizeItem(value: unknown): GuestBagItem | null {
  if (!isRecord(value)) {
    return null;
  }
  const quantity = normalizeQuantity(value.quantity);
  const numericPrice = Number(value.unitPrice);
  if (
    typeof value.productId !== "string"
    || !BUSINESS_ID_PATTERN.test(value.productId)
    || typeof value.skuId !== "string"
    || !BUSINESS_ID_PATTERN.test(value.skuId)
    || typeof value.productTitle !== "string"
    || value.productTitle.trim().length === 0
    || typeof value.skuName !== "string"
    || value.skuName.trim().length === 0
    || typeof value.unitPrice !== "string"
    || !Number.isFinite(numericPrice)
    || numericPrice < 0
    || quantity === null
    || !(value.coverUrl === null || typeof value.coverUrl === "string")
  ) {
    return null;
  }
  return {
    productId: value.productId,
    skuId: value.skuId,
    productTitle: value.productTitle,
    skuName: value.skuName,
    unitPrice: value.unitPrice,
    quantity,
    coverUrl: value.coverUrl,
  };
}

function normalizeMergeItem(value: unknown): GuestBagMergeItem | null {
  if (!isRecord(value)) {
    return null;
  }
  const quantity = normalizeQuantity(value.quantity);
  if (
    typeof value.productId !== "string"
    || !BUSINESS_ID_PATTERN.test(value.productId)
    || typeof value.skuId !== "string"
    || !BUSINESS_ID_PATTERN.test(value.skuId)
    || quantity === null
  ) {
    return null;
  }
  return {
    productId: value.productId,
    skuId: value.skuId,
    quantity,
  };
}

function loadItems(): GuestBagItem[] {
  if (typeof localStorage === "undefined") {
    return [];
  }
  try {
    const value: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "[]");
    if (!Array.isArray(value)) {
      localStorage.removeItem(STORAGE_KEY);
      return [];
    }
    const normalized: GuestBagItem[] = [];
    for (const candidate of value) {
      const item = normalizeItem(candidate);
      if (!item) {
        continue;
      }
      const existing = normalized.find((current) => current.skuId === item.skuId);
      if (existing) {
        if (existing.productId === item.productId) {
          existing.quantity = Math.min(
            MAX_GUEST_QUANTITY,
            existing.quantity + item.quantity,
          );
        }
      } else if (normalized.length < MAX_GUEST_ITEMS) {
        normalized.push(item);
      }
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
    return normalized;
  } catch {
    localStorage.removeItem(STORAGE_KEY);
    return [];
  }
}

function loadPendingMerge(): PendingGuestBagMerge | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  try {
    const value: unknown = JSON.parse(localStorage.getItem(PENDING_MERGE_KEY) ?? "null");
    if (
      !value
      || typeof value !== "object"
      || !("key" in value)
      || !("userId" in value)
      || !("items" in value)
      || typeof value.key !== "string"
      || typeof value.userId !== "string"
      || !Array.isArray(value.items)
    ) {
      localStorage.removeItem(PENDING_MERGE_KEY);
      return null;
    }
    if (
      !MERGE_KEY_PATTERN.test(value.key)
      || !BUSINESS_ID_PATTERN.test(value.userId)
      || value.items.length === 0
      || value.items.length > MAX_GUEST_ITEMS
    ) {
      localStorage.removeItem(PENDING_MERGE_KEY);
      return null;
    }
    const items = value.items.map(normalizeMergeItem);
    const validItems = items.filter((item): item is GuestBagMergeItem => item !== null);
    const uniqueSkuIds = new Set(validItems.map((item) => item.skuId));
    if (validItems.length !== value.items.length || uniqueSkuIds.size !== validItems.length) {
      localStorage.removeItem(PENDING_MERGE_KEY);
      return null;
    }
    return {
      key: value.key,
      userId: value.userId,
      items: validItems,
    };
  } catch {
    localStorage.removeItem(PENDING_MERGE_KEY);
    return null;
  }
}

function newMergeKey(): string {
  return `guest-merge:${secureRandomUUID()}`;
}

export const useBagStore = defineStore("guest-bag", () => {
  const items = ref<GuestBagItem[]>(loadItems());
  const pendingMerge = ref<PendingGuestBagMerge | null>(loadPendingMerge());
  const itemCount = computed(() => items.value.reduce(
    (total, item) => total + item.quantity,
    0,
  ));
  const subtotal = computed(() => items.value.reduce(
    (total, item) => total + Number(item.unitPrice) * item.quantity,
    0,
  ));

  function persist() {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items.value));
    }
  }

  function addItem(item: GuestBagItem) {
    const normalized = normalizeItem(item);
    if (!normalized) {
      throw new Error("商品信息不完整，无法写入当前设备购物袋。");
    }
    const existing = items.value.find((candidate) =>
      candidate.skuId === normalized.skuId);
    if (existing) {
      if (existing.productId !== normalized.productId) {
        throw new Error("同一 SKU 对应了不同商品，当前设备购物袋未修改。");
      }
      existing.quantity = Math.min(
        MAX_GUEST_QUANTITY,
        existing.quantity + normalized.quantity,
      );
    } else {
      if (items.value.length >= MAX_GUEST_ITEMS) {
        throw new Error("当前设备购物袋最多保留 100 种商品。");
      }
      items.value.push(normalized);
    }
    persist();
  }

  function updateQuantity(skuId: BusinessId, quantity: number) {
    const item = items.value.find((candidate) => candidate.skuId === skuId);
    if (!item) {
      return;
    }
    item.quantity = normalizeQuantity(quantity, item.quantity) ?? item.quantity;
    persist();
  }

  function removeItem(skuId: BusinessId): GuestBagItem | null {
    const index = items.value.findIndex((candidate) => candidate.skuId === skuId);
    if (index < 0) {
      return null;
    }
    const [removed] = items.value.splice(index, 1);
    persist();
    return removed ?? null;
  }

  function restoreItem(item: GuestBagItem) {
    const normalized = normalizeItem(item);
    if (!normalized) {
      return;
    }
    const existing = items.value.some((candidate) =>
      candidate.skuId === normalized.skuId);
    if (!existing) {
      if (items.value.length >= MAX_GUEST_ITEMS) {
        return;
      }
      items.value.push(normalized);
      persist();
    }
  }

  function prepareMerge(userId: BusinessId): PendingGuestBagMerge | null {
    if (pendingMerge.value) {
      if (pendingMerge.value.userId !== userId) {
        throw new GuestBagMergeOwnershipError();
      }
      return pendingMerge.value;
    }
    if (items.value.length === 0) {
      return null;
    }
    const prepared: PendingGuestBagMerge = {
      key: newMergeKey(),
      userId,
      items: items.value.map((item) => ({
        productId: item.productId,
        skuId: item.skuId,
        quantity: item.quantity,
      })),
    };
    pendingMerge.value = prepared;
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(PENDING_MERGE_KEY, JSON.stringify(prepared));
    }
    return prepared;
  }

  function completeMerge(key: string): boolean {
    const prepared = pendingMerge.value;
    if (!prepared || prepared.key !== key) {
      return false;
    }
    for (const submitted of prepared.items) {
      const index = items.value.findIndex((item) => item.skuId === submitted.skuId);
      if (index < 0) {
        continue;
      }
      const current = items.value[index];
      if (!current || current.quantity <= submitted.quantity) {
        items.value.splice(index, 1);
      } else {
        current.quantity -= submitted.quantity;
      }
    }
    pendingMerge.value = null;
    persist();
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(PENDING_MERGE_KEY);
    }
    return true;
  }

  return {
    items,
    pendingMerge,
    itemCount,
    subtotal,
    addItem,
    updateQuantity,
    removeItem,
    restoreItem,
    prepareMerge,
    completeMerge,
  };
});
