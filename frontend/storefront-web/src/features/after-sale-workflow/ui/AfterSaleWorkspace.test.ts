import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  AfterSale,
  Refund,
  ReturnReceipt,
} from "@plain-journal/foundation";

import AfterSaleWorkspace from "./AfterSaleWorkspace.vue";

const afterSaleNo = "AS2079000000000003001";
const orderNo = "ORD2079000000000003002";
const returnReceiptNo = "RET2079000000000003003";
const ownerId = "2079000000000000999";

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

function afterSaleFixture(
  status: string,
  overrides: Partial<AfterSale> = {},
): AfterSale {
  return {
    afterSaleNo,
    orderNo,
    userId: ownerId,
    afterSaleType: "RETURN_REFUND",
    status,
    reason: "商品到货后存在明确破损",
    reviewReason: status === "APPLIED" ? null : "符合整单退货退款条件",
    refundAmount: "378.00",
    returnReceiptNo: status === "APPLIED" ? null : returnReceiptNo,
    refundNo: status === "APPLIED" ? null : "REF2079000000000003004",
    items: [{
      lineNo: 1,
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      quantity: 2,
      lineAmount: "378.00",
      discountAmount: "0.00",
      refundableAmount: "378.00",
    }],
    version: 1,
    createdAt: "2026-08-02T00:00:00Z",
    updatedAt: "2026-08-02T00:01:00Z",
    approvedAt: status === "APPLIED" ? null : "2026-08-02T00:01:00Z",
    completedAt: status === "COMPLETED" ? "2026-08-02T01:00:00Z" : null,
    ...overrides,
  };
}

function returnReceiptFixture(
  status: string,
  overrides: Partial<ReturnReceipt> = {},
): ReturnReceipt {
  return {
    returnReceiptNo,
    afterSaleNo,
    orderNo,
    userId: ownerId,
    warehouseId: "2079000000000003999",
    reservationNo: "RES2079000000000003005",
    status,
    refundAmount: "378.00",
    carrier: status === "WAIT_SHIPMENT" ? null : "SF",
    trackingNo: status === "WAIT_SHIPMENT" ? null : "SF1234567890",
    inspectionRemark: null,
    items: [{
      lineNo: 1,
      skuId: "2079000000000000011",
      quantity: 2,
      refundableAmount: "378.00",
    }],
    version: 0,
    createdAt: "2026-08-02T00:00:00Z",
    updatedAt: "2026-08-02T00:01:00Z",
    shippedAt: status === "WAIT_SHIPMENT" ? null : "2026-08-02T00:01:00Z",
    receivedAt: null,
    inspectedAt: null,
    ...overrides,
  };
}

function refundFixture(status: string, requestStatus = "PENDING"): Refund {
  return {
    refundNo: "REF2079000000000003004",
    afterSaleNo,
    orderNo,
    paymentNo: "PAY2079000000000003006",
    userId: ownerId,
    channel: "MOCK",
    status,
    amount: "378.00",
    channelRefundNo: null,
    requestStatus,
    requestAttempts: requestStatus === "NEEDS_ATTENTION" ? 3 : 0,
    nextRequestAt: null,
    requestSentAt: null,
    createdAt: "2026-08-02T00:00:00Z",
    updatedAt: "2026-08-02T00:01:00Z",
    refundedAt: null,
  };
}

async function mountWorkspace(
  fetchHandler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
): Promise<VueWrapper> {
  const pinia = createPinia();
  setActivePinia(pinia);
  vi.stubGlobal("fetch", vi.fn(fetchHandler));

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/after-sales", component: { template: "<div />" } },
      {
        path: "/orders/:orderNo",
        name: "order-detail",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push("/");
  await router.isReady();

  const wrapper = mount(AfterSaleWorkspace, {
    props: {
      afterSaleNo,
      access: {
        authenticated: true,
        ownerId,
        accessToken: "access-token",
      },
    },
    global: { plugins: [pinia, router] },
  });
  await flushPromises();
  return wrapper;
}

describe("AfterSaleWorkspace status semantics", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows shipment success only after Fulfillment returns RETURNING", async () => {
    let currentAfterSale = afterSaleFixture("WAIT_RETURN");
    let currentReturn = returnReceiptFixture("WAIT_SHIPMENT");
    const wrapper = await mountWorkspace(async (input, init) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}`) {
        return success(currentAfterSale);
      }
      if (url.pathname === `/api/v1/fulfillment/returns/${returnReceiptNo}`) {
        if (method === "POST" || url.pathname.endsWith("/shipment")) {
          throw new Error("shipment route must include /shipment");
        }
        return success(currentReturn);
      }
      if (
        url.pathname === `/api/v1/fulfillment/returns/${returnReceiptNo}/shipment`
        && method === "POST"
      ) {
        currentReturn = returnReceiptFixture("RETURNING");
        currentAfterSale = afterSaleFixture("RETURNING");
        return success(currentReturn);
      }
      if (url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`) {
        return success(refundFixture("PROCESSING"));
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });

    await wrapper.get("#return-carrier").setValue("SF");
    await wrapper.get("#return-tracking-no").setValue("SF1234567890");
    await wrapper.get("form.after-sale-shipment").trigger("submit");
    await flushPromises();

    expect(wrapper.find(".after-sale-feedback.pj-status-notice--success").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("寄回事实已确认");
    expect(wrapper.text()).toContain("SF / SF1234567890");
    expect(wrapper.find(".after-sale-feedback.pj-status-notice--unknown").exists())
      .toBe(false);
  });

  it("keeps a lost shipment result unknown and does not render success", async () => {
    const currentAfterSale = afterSaleFixture("WAIT_RETURN");
    const currentReturn = returnReceiptFixture("WAIT_SHIPMENT");
    const wrapper = await mountWorkspace(async (input, init) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}`) {
        return success(currentAfterSale);
      }
      if (url.pathname === `/api/v1/fulfillment/returns/${returnReceiptNo}`) {
        return success(currentReturn);
      }
      if (
        url.pathname === `/api/v1/fulfillment/returns/${returnReceiptNo}/shipment`
        && method === "POST"
      ) {
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      if (url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`) {
        return success(refundFixture("PROCESSING"));
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });

    await wrapper.get("#return-carrier").setValue("SF");
    await wrapper.get("#return-tracking-no").setValue("SF1234567890");
    await wrapper.get("form.after-sale-shipment").trigger("submit");
    await flushPromises();

    expect(wrapper.find(".after-sale-feedback.pj-status-notice--unknown").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("寄回结果待确认");
    expect(wrapper.text()).toContain("不要更换运单号重复提交");
    expect(wrapper.find(".after-sale-feedback.pj-status-notice--success").exists())
      .toBe(false);
  });

  it("keeps an uncertain cancellation unknown instead of success", async () => {
    const currentAfterSale = afterSaleFixture("APPLIED");
    const wrapper = await mountWorkspace(async (input, init) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}/cancel`) {
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}`) {
        return success(currentAfterSale);
      }
      if (url.pathname === "/api/v1/fulfillment/returns") {
        return success([]);
      }
      if (url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`) {
        return failure(404, "RESOURCE_NOT_FOUND", "refund not found");
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });

    const cancel = wrapper.findAll("button").find((button) =>
      button.text() === "取消售后申请");
    await cancel!.trigger("click");
    const confirm = wrapper.findAll("button").find((button) =>
      button.text() === "确认取消申请");
    await confirm!.trigger("click");
    await flushPromises();

    expect(wrapper.find(".after-sale-feedback.pj-status-notice--unknown").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("取消结果待确认");
    expect(wrapper.find(".after-sale-feedback.pj-status-notice--success").exists())
      .toBe(false);
  });

  it("shows cancellation success only after Trade returns CANCELED", async () => {
    let currentAfterSale = afterSaleFixture("APPLIED");
    const wrapper = await mountWorkspace(async (input, init) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}/cancel`) {
        currentAfterSale = afterSaleFixture("CANCELED");
        return success(currentAfterSale);
      }
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}`) {
        return success(currentAfterSale);
      }
      if (url.pathname === "/api/v1/fulfillment/returns") {
        return success([]);
      }
      if (url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`) {
        return failure(404, "RESOURCE_NOT_FOUND", "refund not found");
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });

    const cancel = wrapper.findAll("button").find((button) =>
      button.text() === "取消售后申请");
    await cancel!.trigger("click");
    const confirm = wrapper.findAll("button").find((button) =>
      button.text() === "确认取消申请");
    await confirm!.trigger("click");
    await flushPromises();

    expect(wrapper.find(".after-sale-feedback.pj-status-notice--success").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("售后申请已取消");
    expect(wrapper.find(".after-sale-feedback.pj-status-notice--unknown").exists())
      .toBe(false);
  });

  it("renders PROCESSING as processing and NEEDS_ATTENTION as customer read-only attention", async () => {
    let currentRefund = refundFixture("PROCESSING");
    let currentAfterSale = afterSaleFixture("REFUNDING");
    const wrapper = await mountWorkspace(async (input, init) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === `/api/v1/trade/after-sales/${afterSaleNo}`) {
        return success(currentAfterSale);
      }
      if (url.pathname === `/api/v1/fulfillment/returns/${returnReceiptNo}`) {
        return success(returnReceiptFixture("INSPECTED", {
          receivedAt: "2026-08-02T00:10:00Z",
          inspectedAt: "2026-08-02T00:15:00Z",
          inspectionRemark: "验收通过",
        }));
      }
      if (url.pathname === `/api/v1/payment/refunds/by-after-sale/${afterSaleNo}`) {
        return success(currentRefund);
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });

    expect(wrapper.find(".refund-state.pj-status-notice--processing").exists())
      .toBe(true);
    expect(wrapper.find(".refund-state.pj-status-notice--refunded").exists())
      .toBe(false);
    expect(wrapper.text()).toContain("不会因为仓库已验收就提前显示到账");

    currentAfterSale = afterSaleFixture("REFUND_FAILED");
    currentRefund = refundFixture("PROCESSING", "NEEDS_ATTENTION");
    const refresh = wrapper.findAll("button").find((button) =>
      button.text() === "刷新售后进度");
    await refresh!.trigger("click");
    await flushPromises();

    expect(wrapper.find(".refund-state.pj-status-notice--attention").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("平台会在授权、幂等与审计边界内核对并恢复");
    expect(wrapper.findAll("button").some((button) => button.text().includes("补偿")))
      .toBe(false);
    expect(wrapper.find(".refund-state.pj-status-notice--refunded").exists())
      .toBe(false);
  });
});
