import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useSessionStore } from "../../features/customer-session";
import AddressManagementPage from "./AddressManagementPage.vue";

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

const profile = {
  id: "2079000000000000999",
  email: "reader@example.com",
  displayName: "Reader",
  status: "ACTIVE",
  roles: ["CUSTOMER"],
};

const existingAddress = {
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
};

async function mountPage() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const session = useSessionStore();
  session.profile = profile;
  session.accessToken = "access-token";
  session.initialized = true;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/account", component: { template: "<div />" } },
      { path: "/account/addresses", component: AddressManagementPage },
    ],
  });
  await router.push("/account/addresses");
  await router.isReady();
  const wrapper = mount(AddressManagementPage, {
    global: { plugins: [pinia, router] },
  });
  await flushPromises();
  return { wrapper };
}

async function fillAddressForm(
  wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"],
) {
  await wrapper.get("#recipient-name").setValue("Recovery Address");
  await wrapper.get("#address-phone").setValue("+86 13700000000");
  await wrapper.get("#province").setValue("浙江省");
  await wrapper.get("#province-code").setValue("330000");
  await wrapper.get("#city").setValue("杭州市");
  await wrapper.get("#city-code").setValue("330100");
  await wrapper.get("#district").setValue("上城区");
  await wrapper.get("#district-code").setValue("330102");
  await wrapper.get("#detail-address").setValue("湖滨路 8 号");
  await wrapper.get("#postal-code").setValue("310000");
}

describe("AddressManagementPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("uses the shared visual grammar without replacing owner address facts", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([existingAddress])));
    const { wrapper } = await mountPage();

    expect(wrapper.find(".pj-page-container.address-page").exists()).toBe(true);
    expect(wrapper.find(".pj-surface.address-form").exists()).toBe(true);
    expect(wrapper.findAll(".pj-field")).toHaveLength(10);
    expect(wrapper.get(".address-row").text()).toContain("Test Customer");
    expect(wrapper.get(".address-row").text()).toContain("默认地址");
    expect(wrapper.find(".catalog-header").exists()).toBe(false);
    expect(wrapper.find(".form-error").exists()).toBe(false);
  });

  it("keeps an unknown create form intact and recovers only by rereading Identity", async () => {
    const recoveredAddress = {
      ...existingAddress,
      id: "2079000000000000889",
      recipientName: "Recovery Address",
      phone: "+86 13700000000",
      district: "上城区",
      districtCode: "330102",
      detailAddress: "湖滨路 8 号",
      defaultAddress: false,
    };
    let reads = 0;
    let creates = 0;
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "POST") {
        creates += 1;
        return failure(503, "SERVICE_UNAVAILABLE", "response unavailable");
      }
      reads += 1;
      return success(reads === 1
        ? [existingAddress]
        : [existingAddress, recoveredAddress]);
    }));
    const { wrapper } = await mountPage();
    await fillAddressForm(wrapper);

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const unknown = wrapper.get(".address-error.pj-status-notice--unknown");
    expect(unknown.text()).toContain("地址操作结果待确认");
    expect(unknown.text()).toContain("先重新读取地址");
    expect(wrapper.get<HTMLInputElement>("#recipient-name").element.value)
      .toBe("Recovery Address");
    expect(wrapper.find(".address-feedback.pj-status-notice--success").exists())
      .toBe(false);

    await unknown.get("button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("最新地址列表已重新读取");
    expect(wrapper.text()).toContain("Recovery Address");
    expect(creates).toBe(1);
    expect(reads).toBe(2);
  });
});
