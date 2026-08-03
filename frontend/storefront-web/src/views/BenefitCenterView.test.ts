import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useSessionStore } from "../features/customer-session";
import BenefitCenterView from "./BenefitCenterView.vue";

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

const ownerId = "2079000000000000999";

function benefit(
  benefitNo: string,
  status: string,
  overrides: Record<string, unknown> = {},
) {
  return {
    benefitNo,
    userId: ownerId,
    ruleCode: "COUPON-10",
    benefitType: "COUPON",
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    status,
    lockedOrderNo: null,
    redeemedOrderNo: null,
    validFrom: "2026-08-01T00:00:00Z",
    validUntil: "2026-09-01T00:00:00Z",
    regions: [],
    ...overrides,
  };
}

async function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const session = useSessionStore();
  session.profile = {
    id: ownerId,
    email: "reader@example.com",
    displayName: "Reader",
    status: "ACTIVE",
    roles: ["CUSTOMER"],
  };
  session.accessToken = "access-token";
  session.initialized = true;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/account", component: { template: "<div />" } },
      { path: "/products", component: { template: "<div />" } },
      { path: "/account/benefits", component: BenefitCenterView },
    ],
  });
  await router.push("/account/benefits");
  await router.isReady();
  const wrapper = mount(BenefitCenterView, {
    global: { plugins: [pinia, router] },
  });
  await flushPromises();
  return wrapper;
}

describe("BenefitCenterView", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("keeps available, locked and redeemed benefits visually distinct", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([
      benefit("BEN-AVAILABLE", "AVAILABLE"),
      benefit("BEN-LOCKED", "LOCKED", { lockedOrderNo: "ORD-LOCKED" }),
      benefit("BEN-REDEEMED", "REDEEMED", { redeemedOrderNo: "ORD-USED" }),
    ])));
    const wrapper = await mountView();

    expect(wrapper.findAll(".benefit-row")).toHaveLength(3);
    expect(wrapper.get(".pj-status-notice--success").text()).toContain("可用");
    expect(wrapper.get(".pj-status-notice--processing").text())
      .toContain("订单已锁定");
    expect(wrapper.get(".pj-status-notice--neutral").text()).toContain("已使用");
    expect(wrapper.find(".benefit-card").exists()).toBe(false);
    expect(wrapper.find(".order-status-badge").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("Marketing 权益事实");
  });

  it("rejects cross-owner benefits instead of rendering leaked facts", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([
      {
        ...benefit("BEN-FOREIGN", "AVAILABLE"),
        userId: "2079000000000002999",
      },
    ])));
    const wrapper = await mountView();

    expect(wrapper.findAll(".benefit-row")).toHaveLength(0);
    const error = wrapper.get(".benefit-error.pj-status-notice--danger");
    expect(error.text()).toContain("不属于当前账户");
    expect(wrapper.text()).not.toContain("BEN-FOREIGN");
  });
});
