import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import CheckoutWorkspace from "./CheckoutWorkspace.vue";

const OWNER_ID = "2079000000000000999";
const PRODUCT_ID = "2079000000000000001";
const SKU_ID = "2079000000000000011";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-02T00:00:00Z",
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
    timestamp: "2026-08-02T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function checkoutResponse(
  url: URL,
  init: RequestInit | undefined,
  available: number,
): Response | null {
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
      createdAt: "2026-08-02T00:00:00Z",
      updatedAt: "2026-08-02T00:00:00Z",
    }]);
  }
  if (url.pathname === "/api/v1/trade/cart/items") {
    return success([{
      id: "2079000000000000777",
      productId: PRODUCT_ID,
      skuId: SKU_ID,
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      specJson: "{}",
      unitPrice: "189.00",
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
      version: 1,
      category: {
        id: "2079000000000000101",
        parentId: null,
        name: "随身用品",
        slug: "carry",
        sortOrder: 1,
      },
      brand: {
        id: "2079000000000000201",
        name: "素简记",
        slug: "plain-journal",
      },
      skus: [{
        id: SKU_ID,
        skuCode: "BAG-NATURAL-M",
        name: "自然色 / 中号",
        specJson: "{}",
        salePrice: "189.00",
        marketPrice: "219.00",
        status: "ACTIVE",
        version: 1,
      }],
      media: [],
    });
  }
  if (url.pathname === `/api/v1/inventory/stocks/${SKU_ID}`) {
    return success({
      skuId: SKU_ID,
      onHand: available + 2,
      reserved: 2,
      available,
    });
  }
  if (url.pathname === "/api/v1/marketing/pricing-previews") {
    const input = JSON.parse(String(init?.body)) as { originalAmount: string };
    return success({
      originalAmount: input.originalAmount,
      couponDiscount: "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: "0.00",
      payableAmount: input.originalAmount,
      appliedBenefits: [],
      calculatedAt: "2026-08-02T00:00:00Z",
    });
  }
  return null;
}

function findButton(wrapper: VueWrapper, label: string) {
  return wrapper.findAll("button").find((candidate) => candidate.text() === label);
}

async function mountCheckout() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/bag", component: { template: "<div />" } },
      { path: "/account/addresses", component: { template: "<div />" } },
      {
        path: "/orders/:orderNo",
        name: "order-detail",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push("/bag");
  await router.isReady();
  const wrapper = mount(CheckoutWorkspace, {
    props: {
      access: {
        authenticated: true,
        ownerId: OWNER_ID,
        accessToken: "access-token",
      },
    },
    global: { plugins: [pinia, router] },
  });
  await flushPromises();
  return wrapper;
}

describe("CheckoutWorkspace", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("keeps insufficient authoritative stock as a warning and blocks submission", async () => {
    const orderRequests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/trade/orders") {
        orderRequests.push(url.pathname);
      }
      const response = checkoutResponse(url, init, 1);
      if (response) {
        return response;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const wrapper = await mountCheckout();
    const authorityButton = findButton(
      wrapper,
      "核对实时价格、库存与优惠",
    );
    expect(authorityButton).toBeDefined();
    await authorityButton!.trigger("click");
    await flushPromises();

    const authorityNotice = wrapper.get(
      ".checkout-sidebar__notice.pj-status-notice--warning",
    );
    expect(authorityNotice.text()).toContain("权威核对已完成");
    expect(authorityNotice.text()).toContain("可用库存不足");
    expect(findButton(wrapper, "以当前事实提交订单 →")?.attributes("disabled"))
      .toBeDefined();
    expect(orderRequests).toEqual([]);
  });

  it("shows an unknown order result without success or danger and preserves recovery actions", async () => {
    const orderKeys: string[] = [];
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-0000-0000-000000000501",
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const response = checkoutResponse(url, init, 8);
      if (response) {
        return response;
      }
      if (url.pathname === "/api/v1/trade/orders" && init?.method === "POST") {
        orderKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        throw new TypeError("Failed to fetch");
      }
      if (url.pathname.startsWith("/api/v1/trade/orders/by-idempotency-key/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "order not found");
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const wrapper = await mountCheckout();
    const authorityButton = findButton(
      wrapper,
      "核对实时价格、库存与优惠",
    );
    await authorityButton!.trigger("click");
    await flushPromises();
    const submitButton = findButton(wrapper, "以当前事实提交订单 →");
    expect(submitButton).toBeDefined();
    await submitButton!.trigger("click");
    await flushPromises();

    const unknown = wrapper.get(
      ".checkout-pending.pj-status-notice--unknown",
    );
    expect(unknown.text()).toContain("订单结果尚未确认");
    expect(unknown.text()).toContain("请求键与固定载荷已保留");
    expect(unknown.text()).toContain("查询订单结果");
    expect(unknown.text()).toContain("使用原请求安全重试");
    expect(wrapper.find(".checkout-pending.pj-status-notice--danger").exists())
      .toBe(false);
    expect(wrapper.text()).not.toContain("订单提交成功");
    expect(orderKeys).toEqual([
      "order:00000000-0000-0000-0000-000000000501",
    ]);
    expect(localStorage.getItem(
      `plain-journal:pending-order:v2:${OWNER_ID}`,
    )).not.toBeNull();
  });
});
