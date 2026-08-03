import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  CheckoutAccessChangedError,
  CheckoutDraftChangedError,
  type CheckoutAccessContext,
  useCheckoutStore,
} from "./checkoutStore";

const USER_ID = "2079000000000000999";
const ADDRESS_ID = "2079000000000000888";
const CART_ITEM_ID = "2079000000000000777";
const PRODUCT_ID = "2079000000000000001";
const SKU_ID = "2079000000000000011";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-20T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-07-20T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function checkoutAccess(
  userId = USER_ID,
  accessToken = "access-token",
): CheckoutAccessContext {
  return {
    authenticated: true,
    ownerId: userId,
    accessToken,
  };
}

function pendingKey(userId = USER_ID) {
  return `plain-journal:pending-order:v2:${userId}`;
}

function checkoutBaseResponse(
  url: URL,
  init: RequestInit | undefined,
  options: {
    cartUnitPrice?: string;
    currentUnitPrice?: string;
    available?: number;
  } = {},
): Response | null {
  const cartUnitPrice = options.cartUnitPrice ?? "189.00";
  const currentUnitPrice = options.currentUnitPrice ?? cartUnitPrice;
  if (url.pathname === "/api/v1/identity/addresses") {
    return success([{
      id: ADDRESS_ID,
      recipientName: "Test Customer",
      phone: "+86 13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "文三路 1 号",
      postalCode: "310000",
      defaultAddress: true,
      version: 0,
      createdAt: "2026-07-20T00:00:00Z",
      updatedAt: "2026-07-20T00:00:00Z",
    }]);
  }
  if (url.pathname === "/api/v1/trade/cart/items") {
    return success([{
      id: CART_ITEM_ID,
      productId: PRODUCT_ID,
      skuId: SKU_ID,
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      specJson: "{}",
      unitPrice: cartUnitPrice,
      quantity: 2,
      selected: true,
    }]);
  }
  if (url.pathname === "/api/v1/marketing/benefits") {
    return success([]);
  }
  if (url.pathname === `/api/v1/catalog/products/${PRODUCT_ID}`) {
    return success({
      id: PRODUCT_ID,
      title: "帆布通勤袋",
      subtitle: "轻量日常收纳",
      description: "商品描述",
      status: "ACTIVE",
      version: 2,
      category: {
        id: "2079000000000000201",
        parentId: null,
        name: "通勤",
        slug: "commute",
        sortOrder: 1,
      },
      brand: {
        id: "2079000000000000301",
        name: "素简记",
        slug: "plain-journal",
      },
      skus: [{
        id: SKU_ID,
        skuCode: "BAG-NATURAL-M",
        name: "自然色 / 中号",
        specJson: "{}",
        salePrice: currentUnitPrice,
        marketPrice: null,
        status: "ACTIVE",
        version: 3,
      }],
      media: [],
    });
  }
  if (url.pathname === `/api/v1/inventory/stocks/${SKU_ID}`) {
    const available = options.available ?? 8;
    return success({
      skuId: SKU_ID,
      onHand: available + 2,
      reserved: 2,
      available,
    });
  }
  if (url.pathname === "/api/v1/marketing/pricing-previews") {
    const body = JSON.parse(String(init?.body)) as {
      originalAmount: string;
      benefitNos: string[];
    };
    return success({
      originalAmount: body.originalAmount,
      couponDiscount: "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: "0.00",
      payableAmount: body.originalAmount,
      appliedBenefits: [],
      calculatedAt: "2026-07-20T00:00:00Z",
    });
  }
  return null;
}

function orderFixture(orderNo = "ORD2079000000000000001") {
  return {
    orderNo,
    status: "PENDING_PAYMENT",
    totalAmount: "398.00",
    priceSnapshot: {
      marketingLockNo: "PLK2079000000000000001",
      originalAmount: "398.00",
      couponDiscount: "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: "0.00",
      payableAmount: "398.00",
      pricingVersion: "v1",
      allocations: [],
    },
    paymentDeadline: "2026-07-20T00:15:00Z",
    closeReason: null,
    deliveryAddress: {
      sourceAddressId: ADDRESS_ID,
      recipientName: "Test Customer",
      phone: "+86 13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "文三路 1 号",
      postalCode: "310000",
    },
    items: [{
      lineNo: 1,
      productId: PRODUCT_ID,
      skuId: SKU_ID,
      productTitle: "帆布通勤袋",
      skuCode: "BAG-NATURAL-M",
      skuName: "自然色 / 中号",
      specJson: "{}",
      imageObjectKey: null,
      unitPrice: "199.00",
      quantity: 2,
      lineAmount: "398.00",
      discountAmount: "0.00",
      payableAmount: "398.00",
    }],
    version: 1,
    createdAt: "2026-07-20T00:00:00Z",
    updatedAt: "2026-07-20T00:00:01Z",
  };
}

describe("checkout draft", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("reads address, cart and benefits before using the side-effect-free preview API", async () => {
    const access = checkoutAccess();

    const requestedPaths: string[] = [];
    const previewBodies: Array<Record<string, unknown>> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      requestedPaths.push(url.pathname);
      if (url.pathname === "/api/v1/identity/addresses") {
        return success([{
          id: "2079000000000000888",
          recipientName: "Test Customer",
          phone: "+86 13800000000",
          province: "浙江省",
          provinceCode: "330000",
          city: "杭州市",
          cityCode: "330100",
          district: "西湖区",
          districtCode: "330106",
          detailAddress: "文三路 1 号",
          postalCode: "310000",
          defaultAddress: true,
          version: 0,
          createdAt: "2026-07-20T00:00:00Z",
          updatedAt: "2026-07-20T00:00:00Z",
        }]);
      }
      if (url.pathname === "/api/v1/trade/cart/items") {
        return success([{
          id: "2079000000000000777",
          productId: "2079000000000000001",
          skuId: "2079000000000000011",
          productTitle: "帆布通勤袋",
          skuName: "自然色 / 中号",
          specJson: "{}",
          unitPrice: "189.00",
          quantity: 2,
          selected: true,
        }]);
      }
      if (url.pathname === "/api/v1/marketing/benefits") {
        return success([{
          benefitNo: "BEN-001",
          userId: "2079000000000000999",
          ruleCode: "COUPON-10",
          benefitType: "COUPON",
          thresholdAmount: "100.00",
          discountAmount: "10.00",
          status: "AVAILABLE",
          lockedOrderNo: null,
          redeemedOrderNo: null,
          validFrom: "2026-07-19T00:00:00Z",
          validUntil: "2026-07-30T00:00:00Z",
          regions: [],
        }]);
      }
      if (url.pathname === "/api/v1/marketing/pricing-previews") {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        previewBodies.push(body);
        const selected = (body.benefitNos as string[]).length > 0;
        return success({
          originalAmount: "378.00",
          couponDiscount: selected ? "10.00" : "0.00",
          redPacketDiscount: "0.00",
          subsidyDiscount: "0.00",
          discountAmount: selected ? "10.00" : "0.00",
          payableAmount: selected ? "368.00" : "378.00",
          appliedBenefits: [],
          calculatedAt: "2026-07-20T00:00:00Z",
        });
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);

    expect(checkout.originalAmount).toBe("378.00");
    expect(checkout.preview?.payableAmount).toBe("378.00");
    expect(previewBodies[0]).toMatchObject({
      originalAmount: "378.00",
      benefitNos: [],
    });

    checkout.toggleBenefit(checkout.availableBenefits[0]!);
    await checkout.refreshPreview(access);

    expect(checkout.preview?.payableAmount).toBe("368.00");
    expect(previewBodies[1]).toMatchObject({ benefitNos: ["BEN-001"] });
    expect(requestedPaths).not.toContain("/api/v1/trade/orders");
    expect(requestedPaths.every((path) => !path.startsWith("/api/v1/inventory"))).toBe(true);
  });

  it("rechecks Catalog, Inventory and Marketing with the current price before submit", async () => {
    const access = checkoutAccess();
    const previewAmounts: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/marketing/pricing-previews") {
        const body = JSON.parse(String(init?.body)) as { originalAmount: string };
        previewAmounts.push(body.originalAmount);
      }
      const response = checkoutBaseResponse(url, init, {
        cartUnitPrice: "189.00",
        currentUnitPrice: "199.00",
        available: 6,
      });
      if (response) {
        return response;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    await checkout.refreshAuthority(access);

    expect(previewAmounts).toEqual(["378.00", "398.00"]);
    expect(checkout.authority).toMatchObject({
      lines: [{
        productId: PRODUCT_ID,
        skuId: SKU_ID,
        currentUnitPrice: "199.00",
        available: 6,
        priceChanged: true,
      }],
      preview: {
        originalAmount: "398.00",
        payableAmount: "398.00",
      },
    });
    expect(checkout.authorityReady).toBe(true);
    expect(checkout.authorityHasPriceChanges).toBe(true);
  });

  it("blocks order submission when the authoritative stock is insufficient", async () => {
    const access = checkoutAccess();
    const requestedOrders: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/trade/orders" && init?.method === "POST") {
        requestedOrders.push(url.pathname);
      }
      const response = checkoutBaseResponse(url, init, { available: 1 });
      if (response) {
        return response;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    await checkout.refreshAuthority(access);
    const result = await checkout.submitOrder(access);

    expect(checkout.authorityReady).toBe(false);
    expect(result).toBeNull();
    expect(checkout.submissionError).toContain("库存不足");
    expect(requestedOrders).toHaveLength(0);
    expect(localStorage.getItem(pendingKey())).toBeNull();
  });

  it("recovers a lost create response by the stable idempotency key", async () => {
    const access = checkoutAccess();
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-0000-0000-000000000101",
    });
    const createKeys: string[] = [];
    const lookupPaths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const response = checkoutBaseResponse(url, init, {
        currentUnitPrice: "199.00",
      });
      if (response) {
        return response;
      }
      if (url.pathname === "/api/v1/trade/orders" && init?.method === "POST") {
        createKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        throw new Error("response lost after server commit");
      }
      if (url.pathname.startsWith("/api/v1/trade/orders/by-idempotency-key/")) {
        lookupPaths.push(url.pathname);
        return success(orderFixture());
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    await checkout.refreshAuthority(access);
    const result = await checkout.submitOrder(access);

    expect(result?.orderNo).toBe("ORD2079000000000000001");
    expect(createKeys).toEqual([
      "order:00000000-0000-0000-0000-000000000101",
    ]);
    expect(lookupPaths).toEqual([
      "/api/v1/trade/orders/by-idempotency-key/order%3A00000000-0000-0000-0000-000000000101",
    ]);
    expect(checkout.pendingSubmission).toBeNull();
    expect(checkout.submissionUnknown).toBe(false);
  });

  it("keeps an unknown result and safely retries the original request key", async () => {
    const access = checkoutAccess();
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-0000-0000-000000000102",
    });
    const createKeys: string[] = [];
    let createAttempts = 0;
    let lookupAttempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const response = checkoutBaseResponse(url, init, {
        currentUnitPrice: "199.00",
      });
      if (response) {
        return response;
      }
      if (url.pathname === "/api/v1/trade/orders" && init?.method === "POST") {
        createAttempts += 1;
        createKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        if (createAttempts === 1) {
          throw new Error("response lost");
        }
        return success(orderFixture());
      }
      if (url.pathname.startsWith("/api/v1/trade/orders/by-idempotency-key/")) {
        lookupAttempts += 1;
        return failure(404, "RESOURCE_NOT_FOUND", "order not found");
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    await checkout.refreshAuthority(access);
    const firstResult = await checkout.submitOrder(access);
    const storedAfterUnknown = JSON.parse(
      localStorage.getItem(pendingKey()) ?? "null",
    ) as { key: string; request: unknown } | null;

    expect(firstResult).toBeNull();
    expect(checkout.submissionUnknown).toBe(true);
    expect(storedAfterUnknown).toMatchObject({
      key: "order:00000000-0000-0000-0000-000000000102",
      request: {
        addressId: ADDRESS_ID,
        items: [{
          productId: PRODUCT_ID,
          skuId: SKU_ID,
          quantity: 2,
        }],
      },
    });

    const retried = await checkout.submitOrder(access);

    expect(retried?.orderNo).toBe("ORD2079000000000000001");
    expect(createKeys).toEqual([
      "order:00000000-0000-0000-0000-000000000102",
      "order:00000000-0000-0000-0000-000000000102",
    ]);
    expect(lookupAttempts).toBe(2);
    expect(localStorage.getItem(pendingKey())).toBeNull();
  });

  it("keeps another account's legacy pending order private and unclaimed", async () => {
    localStorage.setItem("plain-journal:pending-order:v1", JSON.stringify({
      key: "order:00000000-0000-0000-0000-000000000103",
      userId: USER_ID,
      request: {
        addressId: ADDRESS_ID,
        items: [{
          productId: PRODUCT_ID,
          skuId: SKU_ID,
          quantity: 2,
        }],
        benefitNos: [],
      },
      createdAt: "2026-07-20T00:00:00Z",
    }));
    const otherUserId = "2079000000000001999";
    const access = checkoutAccess(otherUserId, "other-access-token");
    const fetchMock = vi.fn(async () => {
      throw new Error("No request should be sent for another account");
    });
    vi.stubGlobal("fetch", fetchMock);

    const checkout = useCheckoutStore();
    const result = await checkout.submitOrder(access);

    expect(result).toBeNull();
    expect(checkout.pendingSubmission).toBeNull();
    expect(checkout.submissionError).toContain("价格、库存和优惠资格核对");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(localStorage.getItem("plain-journal:pending-order:v1")).not.toBeNull();
    expect(localStorage.getItem(pendingKey(otherUserId))).toBeNull();
  });

  it("discards an old owner's late checkout response after the account changes", async () => {
    const firstAccess = checkoutAccess(USER_ID, "first-access-token");
    const secondUserId = "2079000000000001999";
    const secondAccess = checkoutAccess(secondUserId, "second-access-token");
    let releaseFirstBenefits!: (response: Response) => void;
    const firstBenefits = new Promise<Response>((resolve) => {
      releaseFirstBenefits = resolve;
    });
    let firstBenefitRequested!: () => void;
    const firstBenefitRequest = new Promise<void>((resolve) => {
      firstBenefitRequested = resolve;
    });

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const authorization = new Headers(init?.headers).get("Authorization");
      if (
        url.pathname === "/api/v1/marketing/benefits"
        && authorization === "Bearer first-access-token"
      ) {
        firstBenefitRequested();
        return firstBenefits;
      }
      const response = checkoutBaseResponse(url, init);
      if (response) {
        return response;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    const firstLoad = checkout.load(firstAccess);
    await firstBenefitRequest;
    await checkout.load(secondAccess);
    releaseFirstBenefits(success([{
      benefitNo: "BEN-FIRST-ONLY",
      userId: USER_ID,
      ruleCode: "FIRST-ONLY",
      benefitType: "COUPON",
      thresholdAmount: "100.00",
      discountAmount: "10.00",
      status: "AVAILABLE",
      lockedOrderNo: null,
      redeemedOrderNo: null,
      validFrom: "2026-07-20T00:00:00Z",
      validUntil: "2026-07-30T00:00:00Z",
      regions: [],
    }]));

    await expect(firstLoad).rejects.toBeInstanceOf(CheckoutAccessChangedError);
    expect(checkout.activeOwnerId).toBe(secondUserId);
    expect(checkout.availableBenefits).toEqual([]);
    expect(checkout.error).toBeNull();
  });

  it("does not let a stale pricing response restore an invalidated draft", async () => {
    const access = checkoutAccess();
    let deferredPreview = false;
    let releasePreview!: (response: Response) => void;
    const previewResponse = new Promise<Response>((resolve) => {
      releasePreview = resolve;
    });
    let previewRequested!: () => void;
    const previewRequest = new Promise<void>((resolve) => {
      previewRequested = resolve;
    });

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/marketing/pricing-previews" && deferredPreview) {
        previewRequested();
        return previewResponse;
      }
      const response = checkoutBaseResponse(url, init);
      if (response) {
        return response;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    deferredPreview = true;
    const stalePreview = checkout.refreshPreview(access);
    await previewRequest;
    checkout.selectAddress(ADDRESS_ID);
    releasePreview(success({
      originalAmount: "378.00",
      couponDiscount: "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: "0.00",
      payableAmount: "378.00",
      appliedBenefits: [],
      calculatedAt: "2026-07-20T00:00:01Z",
    }));

    await expect(stalePreview).rejects.toBeInstanceOf(CheckoutDraftChangedError);
    expect(checkout.preview).toBeNull();
    expect(checkout.previewError).toBeNull();
    expect(checkout.authority).toBeNull();
  });

  it("coalesces concurrent submits onto one Trade request and one stable key", async () => {
    const access = checkoutAccess();
    let releaseCreate!: (response: Response) => void;
    const createResponse = new Promise<Response>((resolve) => {
      releaseCreate = resolve;
    });
    let createRequested!: () => void;
    const createRequest = new Promise<void>((resolve) => {
      createRequested = resolve;
    });
    const createKeys: string[] = [];

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const response = checkoutBaseResponse(url, init, { currentUnitPrice: "199.00" });
      if (response) {
        return response;
      }
      if (url.pathname === "/api/v1/trade/orders" && init?.method === "POST") {
        createKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        createRequested();
        return createResponse;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const checkout = useCheckoutStore();
    await checkout.load(access);
    await checkout.refreshAuthority(access);
    const firstSubmit = checkout.submitOrder(access);
    const secondSubmit = checkout.submitOrder(access);
    await createRequest;
    releaseCreate(success(orderFixture()));

    const [firstOrder, secondOrder] = await Promise.all([firstSubmit, secondSubmit]);
    expect(firstOrder?.orderNo).toBe("ORD2079000000000000001");
    expect(secondOrder?.orderNo).toBe(firstOrder?.orderNo);
    expect(createKeys).toHaveLength(1);
    expect(createKeys[0]).toMatch(/^order:/u);
    expect(localStorage.getItem(pendingKey())).toBeNull();
  });
});
