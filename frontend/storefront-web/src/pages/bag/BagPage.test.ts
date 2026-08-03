import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAccountCartStore } from "../../entities/account-cart";
import { useSessionStore } from "../../features/customer-session";
import BagPage from "./BagPage.vue";

const OWNER_ID = "2079000000000000999";

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

function seedGuestBag() {
  localStorage.setItem("plain-journal:guest-bag:v1", JSON.stringify([{
    productId: "2079000000000000001",
    skuId: "2079000000000000011",
    productTitle: "帆布通勤袋",
    skuName: "自然色 / 中号",
    unitPrice: "189.00",
    quantity: 2,
    coverUrl: "/images/catalog/canvas-commuter-tote.png",
  }]));
}

function createBagRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/products", component: { template: "<div />" } },
      {
        path: "/products/:productId",
        name: "product-detail",
        component: { template: "<div />" },
      },
      { path: "/bag", component: BagPage },
      { path: "/checkout", component: { template: "<div />" } },
      { path: "/login", name: "login", component: { template: "<div />" } },
    ],
  });
}

describe("BagPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("keeps current-device products distinct from the account subtotal", async () => {
    seedGuestBag();
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.profile = {
      id: OWNER_ID,
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    };
    session.accessToken = "access-token";
    session.initialized = true;

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname === "/api/v1/trade/cart/items") {
        return success([{
          id: "2079000000000002777",
          productId: "2079000000000002001",
          skuId: "2079000000000002011",
          productTitle: "青灰随行本",
          skuName: "远天蓝 / A5",
          specJson: "{}",
          unitPrice: "89.00",
          quantity: 1,
          selected: true,
        }]);
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const router = createBagRouter();
    await router.push("/bag");
    await router.isReady();
    const wrapper = mount(BagPage, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    expect(wrapper.get(".bag-intro__count").text()).toContain("1件商品");
    expect(wrapper.get(".bag-summary__amount").text()).toContain("¥89.00");
    expect(wrapper.get(".bag-summary__amount").text()).not.toContain("¥467.00");
    expect(wrapper.get(".bag-device-pending").text()).toContain("2 件");
    expect(wrapper.get(".bag-device-pending").text())
      .toContain("这些商品不计入上方账户小计");

    const accountCart = useAccountCartStore();
    accountCart.mutationStatus = "unknown";
    accountCart.mutationMessage = "购物车修改结果尚未确认。请先重新读取 Trade 事实。";
    await nextTick();

    expect(wrapper.get(".bag-notice.pj-status-notice--unknown").text())
      .toContain("购物车结果尚未确认");
    expect(wrapper.find(".bag-notice.pj-status-notice--danger").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("账户购物车已更新");
  });

  it("shows the current-device image and reversible removal without implying stock lock", async () => {
    seedGuestBag();
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createBagRouter();
    await router.push("/bag");
    await router.isReady();
    const wrapper = mount(BagPage, {
      global: { plugins: [pinia, router] },
    });

    const image = wrapper.get(".guest-bag-row__media img");
    expect(image.attributes("src")).toBe(
      "/images/catalog/canvas-commuter-tote.png",
    );
    expect(image.attributes("alt")).toBe("帆布通勤袋");
    expect(wrapper.get(".bag-summary__amount").text()).toContain("¥378.00");
    expect(wrapper.text()).toContain("购物袋不代表库存已锁定");

    const remove = wrapper.findAll("button").find((candidate) =>
      candidate.text() === "移出");
    expect(remove).toBeDefined();
    await remove!.trigger("click");
    expect(wrapper.text()).toContain("已移出 帆布通勤袋");
    expect(wrapper.text()).toContain("撤销");
  });
});
