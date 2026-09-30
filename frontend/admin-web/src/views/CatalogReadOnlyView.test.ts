import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import CatalogReadOnlyView from "./CatalogReadOnlyView.vue";
import { useStaffSessionStore } from "../stores/session";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-10-01T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const category = {
  id: "2087000000000000101",
  parentId: null,
  name: "随身用品",
  slug: "carry",
  sortOrder: 1,
};
const brand = {
  id: "2087000000000000301",
  name: "素简记",
  slug: "plain-journal",
};
const product = {
  id: "2087000000000000201",
  title: "青荷通勤袋",
  subtitle: "经营商品事实",
  status: "DRAFT",
  version: 0,
  category,
  brand,
  minimumPrice: "189.00",
  coverUrl: null,
};
const detail = {
  ...product,
  description: "完整 owner 详情",
  skus: [{
    id: "2087000000000000401",
    skuCode: "BAG-BLACK",
    name: "黑色",
    specJson: "{}",
    salePrice: "189.00",
    marketPrice: null,
    status: "ACTIVE",
    version: 0,
  }],
  media: [],
};

describe("catalog management workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads owner facts, filters all statuses, and creates with an idempotency key", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      requests.push({ url: `${url.pathname}${url.search}`, init });
      if (url.pathname.endsWith("/categories")) return success([category]);
      if (url.pathname.endsWith("/brands")) return success([brand]);
      if (url.pathname === "/api/v1/catalog/admin/products" && init?.method === "POST") {
        return success(detail);
      }
      if (url.pathname === "/api/v1/catalog/admin/products") {
        return success({ items: [product], page: 1, size: 20, total: 1 });
      }
      if (url.pathname.endsWith(`/${product.id}`)) return success(detail);
      throw new Error(`unexpected request ${url.pathname}`);
    }));

    const session = useStaffSessionStore();
    session.profile = {
      id: "2087000000000000001",
      email: "operator@example.com",
      displayName: "Operator",
      status: "ACTIVE",
      roles: ["OPERATOR"],
    };
    session.accessToken = "operator-token";
    session.initialized = true;

    const wrapper = mount(CatalogReadOnlyView);
    await flushPromises();

    expect(wrapper.find(".list-workbench__filters").exists()).toBe(true);
    expect(wrapper.find(".list-workbench__list").exists()).toBe(true);
    expect(wrapper.find(".list-workbench__detail").exists()).toBe(true);
    expect(wrapper.text()).toContain("商品经营");
    expect(wrapper.text()).toContain("DRAFT · v0");
    expect((wrapper.get("#edit-description").element as HTMLTextAreaElement).value)
      .toBe("完整 owner 详情");
    expect(wrapper.text()).toContain("BAG-BLACK");

    const initialCategoryReads = requests.filter((request) =>
      request.url.endsWith("/categories")).length;
    const refreshButton = wrapper.findAll("button").find((button) =>
      button.text().includes("重新读取 owner 事实"));
    expect(refreshButton).toBeDefined();
    await refreshButton!.trigger("click");
    await flushPromises();
    expect(requests.filter((request) => request.url.endsWith("/categories")).length)
      .toBe(initialCategoryReads + 1);

    await wrapper.get("#catalog-status").setValue("DRAFT");
    await wrapper.get("#catalog-keyword").setValue("青荷");
    await wrapper.findAll("form")[0]!.trigger("submit");
    await flushPromises();
    expect(requests.some((request) => request.url.includes(
      "/api/v1/catalog/admin/products?page=1&size=20&status=DRAFT&keyword=%E9%9D%92%E8%8D%B7",
    ))).toBe(true);

    await wrapper.get("#create-category").setValue(category.id);
    await wrapper.get("#create-brand").setValue(brand.id);
    await wrapper.get("#create-title").setValue("青荷通勤袋");
    await wrapper.get("#create-subtitle").setValue("经营商品事实");
    await wrapper.get("#create-description").setValue("完整 owner 详情");
    await wrapper.get("#create-sku-code").setValue("BAG-BLACK");
    await wrapper.get("#create-sku-name").setValue("黑色");
    await wrapper.get("#create-sale").setValue("189.00");
    await wrapper.findAll("form")[1]!.trigger("submit");
    await flushPromises();

    const createRequest = requests.find((request) =>
      request.url === "/api/v1/catalog/admin/products"
      && request.init?.method === "POST");
    expect(createRequest).toBeDefined();
    expect(new Headers(createRequest?.init?.headers).get("Idempotency-Key"))
      .toMatch(/^catalog:create:/u);
    expect(wrapper.text()).toContain("命令结果已确认");
  });
});
