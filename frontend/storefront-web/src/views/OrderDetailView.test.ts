import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";

import OrderDetailView from "./OrderDetailView.vue";
import { useSessionStore } from "../features/customer-session";

const ORDER_NO = "ORD2079000000000000001";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-21T00:00:00Z",
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
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function orderFixture(status: string) {
  return {
    orderNo: ORDER_NO,
    status,
    totalAmount: "398.00",
    priceSnapshot: null,
    paymentDeadline: "2026-07-21T00:15:00Z",
    closeReason: status === "CANCELED" ? "USER_CANCELED" : null,
    deliveryAddress: {
      sourceAddressId: "2079000000000000888",
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
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
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
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:00:01Z",
  };
}

function fulfillmentFixture(status: string) {
  return {
    fulfillmentNo: "FUL2079000000000000002",
    orderNo: ORDER_NO,
    userId: "2079000000000000999",
    deliveryAddress: orderFixture("SHIPPED").deliveryAddress,
    status,
    carrier: "MOCK_EXPRESS",
    trackingNo: "TRACK-001",
    history: [
      {
        fromStatus: null,
        toStatus: "CREATED",
        command: "CREATE_FULFILLMENT",
        reason: null,
        operatorType: "SYSTEM",
        operatorId: "trade-service",
        createdAt: "2026-07-21T00:00:00Z",
      },
      {
        fromStatus: "PACKED",
        toStatus: status,
        command: status === "SIGNED" ? "CONFIRM_RECEIPT" : "SHIP",
        reason: null,
        operatorType: status === "SIGNED" ? "CUSTOMER" : "WAREHOUSE",
        operatorId: "2079000000000000999",
        createdAt: "2026-07-21T00:01:00Z",
      },
    ],
    traces: status === "SIGNED" ? [
      {
        externalEventId: `customer-confirm:${ORDER_NO}`,
        nodeType: "SIGNED",
        description: "Customer confirmed receipt",
        locationName: null,
        longitude: null,
        latitude: null,
        occurredAt: "2026-07-21T00:02:00Z",
      },
    ] : [
      {
        externalEventId: "carrier-event-001",
        nodeType: "TRANSIT",
        description: "包裹到达杭州转运中心",
        locationName: "杭州市",
        longitude: "120.155070",
        latitude: "30.274085",
        occurredAt: "2026-07-21T00:01:00Z",
      },
    ],
    version: status === "SIGNED" ? 4 : 3,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:02:00Z",
    pickedAt: "2026-07-21T00:00:20Z",
    packedAt: "2026-07-21T00:00:40Z",
    shippedAt: "2026-07-21T00:01:00Z",
    signedAt: status === "SIGNED" ? "2026-07-21T00:02:00Z" : null,
  };
}

function shipmentPositionFixture() {
  return {
    fulfillmentNo: "FUL2079000000000000002",
    orderNo: ORDER_NO,
    externalEventId: "carrier-event-001",
    nodeType: "TRANSIT",
    locationName: "杭州市",
    longitude: "120.155070",
    latitude: "30.274085",
    occurredAt: "2026-07-21T00:01:00Z",
  };
}

describe("order detail cancellation", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("requires confirmation and keeps CANCELING distinct from CANCELED", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      methods.push(`${method} ${url.pathname}`);
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      return success(orderFixture(method === "POST" ? "CANCELING" : "PENDING_PAYMENT"));
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("库存已经预占");
    expect(methods).toEqual([
      `GET /api/v1/trade/orders/${ORDER_NO}`,
      `GET /api/v1/payment/payments/by-order/${ORDER_NO}`,
    ]);

    const cancelButton = wrapper.findAll("button").find((button) =>
      button.text() === "取消订单");
    expect(cancelButton).toBeDefined();
    await cancelButton!.trigger("click");

    expect(wrapper.text()).toContain("确定不再支付这笔订单吗");
    expect(methods).toHaveLength(2);

    const confirmButton = wrapper.findAll("button").find((button) =>
      button.text() === "确认取消订单");
    expect(confirmButton).toBeDefined();
    await confirmButton!.trigger("click");
    await flushPromises();

    expect(methods).toEqual([
      `GET /api/v1/trade/orders/${ORDER_NO}`,
      `GET /api/v1/payment/payments/by-order/${ORDER_NO}`,
      `POST /api/v1/trade/orders/${ORDER_NO}/cancel`,
    ]);
    expect(wrapper.text()).toContain("订单正在取消");
    expect(wrapper.text()).toContain("完成前不会提前显示取消成功");
    expect(wrapper.text()).not.toContain("订单已取消");
  });

  it("creates a PROCESSING payment without claiming payment success", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture("PENDING_PAYMENT"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (url.pathname === "/api/v1/payment/payments" && init?.method === "POST") {
        return success({
          paymentNo: "PAY2079000000000000002",
          orderNo: ORDER_NO,
          channel: "MOCK",
          status: "PROCESSING",
          amount: "398.00",
          channelTransactionNo: null,
          paidAt: null,
          createdAt: "2026-07-21T00:00:00Z",
          updatedAt: "2026-07-21T00:00:00Z",
        });
      }
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const createButton = wrapper.findAll("button").find((button) =>
      button.text() === "创建支付单");
    expect(createButton).toBeDefined();
    await createButton!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("支付结果正在确认");
    expect(wrapper.text()).toContain("等待独立回调");
    expect(wrapper.find(".payment-feedback.pj-status-notice--processing").exists())
      .toBe(true);
    expect(wrapper.find(".payment-feedback.pj-status-notice--success").exists())
      .toBe(false);
    expect(wrapper.text()).not.toContain("Payment 已确认有效成功回调");
    expect(wrapper.find(".order-status-badge--success").exists()).toBe(false);
    expect(wrapper.findAll("button").some((button) => button.text() === "取消订单")).toBe(false);
    expect(wrapper.text()).toContain("暂不开放顾客取消");
  });

  it("renders a confirmed payment failure as warning instead of success", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture("PENDING_PAYMENT"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (url.pathname === "/api/v1/payment/payments" && init?.method === "POST") {
        return success({
          paymentNo: "PAY2079000000000000003",
          orderNo: ORDER_NO,
          channel: "MOCK",
          status: "FAILED",
          amount: "398.00",
          channelTransactionNo: "MOCK-FAILED-001",
          paidAt: null,
          createdAt: "2026-07-21T00:00:00Z",
          updatedAt: "2026-07-21T00:00:10Z",
        });
      }
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const createButton = wrapper.findAll("button").find((button) =>
      button.text() === "创建支付单");
    await createButton!.trigger("click");
    await flushPromises();

    expect(wrapper.find(".payment-feedback.pj-status-notice--warning").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("支付失败已确认");
    expect(wrapper.find(".payment-feedback.pj-status-notice--success").exists())
      .toBe(false);
  });

  it("keeps an uncertain payment creation visibly unknown", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture("PENDING_PAYMENT"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (url.pathname.startsWith("/api/v1/payment/payments/by-idempotency-key/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (url.pathname === "/api/v1/payment/payments" && init?.method === "POST") {
        return failure(
          503,
          "REMOTE_DEPENDENCY_UNAVAILABLE",
          "payment response unavailable",
        );
      }
      throw new Error(`Unexpected request: ${init?.method ?? "GET"} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const createButton = wrapper.findAll("button").find((button) =>
      button.text() === "创建支付单");
    await createButton!.trigger("click");
    await flushPromises();

    expect(wrapper.find(".payment-feedback.pj-status-notice--unknown").exists())
      .toBe(true);
    expect(wrapper.text()).toContain("原支付键已保留");
    expect(wrapper.find(".payment-feedback.pj-status-notice--success").exists())
      .toBe(false);
  });

  it("shows PAYMENT_EXCEPTION as attention without implying fulfillment will start", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      requests.push(`${method} ${url.pathname}`);
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture("PAYMENT_EXCEPTION"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return success({
          paymentNo: "PAY2079000000000000008",
          orderNo: ORDER_NO,
          channel: "MOCK",
          status: "SUCCESS",
          amount: "398.00",
          channelTransactionNo: "MOCK-LATE-TXN",
          paidAt: "2026-07-21T00:10:00Z",
          createdAt: "2026-07-21T00:05:00Z",
          updatedAt: "2026-07-21T00:10:00Z",
        });
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("订单需要人工核对");
    expect(wrapper.find(".pj-status-notice--attention").exists()).toBe(true);
    expect(wrapper.text()).toContain("支付成功");
    expect(wrapper.text()).not.toContain("履约与物流");
    expect(wrapper.text()).not.toContain("配送信息正在建立");
    expect(requests).toEqual([
      `GET /api/v1/trade/orders/${ORDER_NO}`,
      `GET /api/v1/payment/payments/by-order/${ORDER_NO}`,
    ]);
  });

  it("shows append-only logistics and confirms receipt only after Fulfillment returns SIGNED", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    const requests: string[] = [];
    let confirmed = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      requests.push(`${method} ${url.pathname}`);
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture(confirmed ? "COMPLETED" : "SHIPPED"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return success({
          paymentNo: "PAY2079000000000000002",
          orderNo: ORDER_NO,
          channel: "MOCK",
          status: "SUCCESS",
          amount: "398.00",
          channelTransactionNo: "MOCK-TXN-001",
          paidAt: "2026-07-21T00:00:30Z",
          createdAt: "2026-07-21T00:00:00Z",
          updatedAt: "2026-07-21T00:00:30Z",
        });
      }
      if (url.pathname.endsWith("/confirm-receipt") && method === "POST") {
        confirmed = true;
        return success(fulfillmentFixture("SIGNED"));
      }
      if (url.pathname.endsWith("/position")) {
        return success(shipmentPositionFixture());
      }
      if (url.pathname.startsWith("/api/v1/fulfillment/orders/")) {
        return success(fulfillmentFixture(confirmed ? "SIGNED" : "SHIPPED"));
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("包裹已经发出");
    expect(wrapper.text()).toContain("包裹到达杭州转运中心");
    expect(wrapper.text()).toContain("TRACK-001");
    expect(wrapper.text()).toContain("最新可定位节点");
    expect(wrapper.text()).toContain("120.1551, 30.2741");
    expect(requests).not.toContain(
      `POST /api/v1/fulfillment/orders/${ORDER_NO}/confirm-receipt`,
    );

    const receiptButton = wrapper.findAll("button").find((button) =>
      button.text() === "确认收货");
    expect(receiptButton).toBeDefined();
    await receiptButton!.trigger("click");
    expect(wrapper.text()).toContain("只有实际收到并核对商品后再确认");

    const confirmButton = wrapper.findAll("button").find((button) =>
      button.text() === "确认已经收货");
    expect(confirmButton).toBeDefined();
    await confirmButton!.trigger("click");
    await flushPromises();

    expect(requests).toContain(
      `POST /api/v1/fulfillment/orders/${ORDER_NO}/confirm-receipt`,
    );
    expect(wrapper.text()).toContain("签收事实已经确认");
    expect(wrapper.text()).toContain("订单已完成");
    expect(wrapper.findAll("button").some((button) => button.text() === "确认收货"))
      .toBe(false);
  });

  it("keeps a lost receipt confirmation unknown until Fulfillment proves SIGNED", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: "2079000000000000999",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname.startsWith("/api/v1/trade/orders/")) {
        return success(orderFixture("SHIPPED"));
      }
      if (url.pathname.includes("/api/v1/payment/payments/by-order/")) {
        return success({
          paymentNo: "PAY2079000000000000002",
          orderNo: ORDER_NO,
          channel: "MOCK",
          status: "SUCCESS",
          amount: "398.00",
          channelTransactionNo: "MOCK-TXN-001",
          paidAt: "2026-07-21T00:00:30Z",
          createdAt: "2026-07-21T00:00:00Z",
          updatedAt: "2026-07-21T00:00:30Z",
        });
      }
      if (url.pathname.endsWith("/confirm-receipt") && method === "POST") {
        return failure(
          503,
          "REMOTE_DEPENDENCY_UNAVAILABLE",
          "confirmation response unavailable",
        );
      }
      if (url.pathname.endsWith("/position")) {
        return success(shipmentPositionFixture());
      }
      if (url.pathname.startsWith("/api/v1/fulfillment/orders/")) {
        return success(fulfillmentFixture("SHIPPED"));
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", component: { template: "<div>orders</div>" } },
        { path: "/orders/:orderNo", component: OrderDetailView },
        { path: "/products", component: { template: "<div>products</div>" } },
      ],
    });
    await router.push(`/orders/${ORDER_NO}`);
    await router.isReady();

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const receiptButton = wrapper.findAll("button").find((button) =>
      button.text() === "确认收货");
    await receiptButton!.trigger("click");
    const confirmButton = wrapper.findAll("button").find((button) =>
      button.text() === "确认已经收货");
    await confirmButton!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("确认收货结果待确认");
    expect(wrapper.text()).toContain("页面不会提前");
    expect(wrapper.find(".pj-status-notice--unknown").exists()).toBe(true);
    expect(wrapper.find(".fulfillment-feedback.pj-status-notice--success").exists())
      .toBe(false);
    expect(wrapper.text()).not.toContain("订单已完成");
  });
});
