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
    timestamp: "2026-08-03T00:00:00Z",
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

const product = {
  id: "2087000000000000201",
  title: "青荷通勤袋",
  subtitle: "公开 ACTIVE 商品投影",
  category,
  brand: {
    id: "2087000000000000301",
    name: "素简记",
    slug: "plain-journal",
  },
  minimumPrice: "189.00",
  coverUrl: "/images/catalog/canvas-commuter-tote.png",
};

describe("catalog read-only list workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads, filters, and refreshes the public catalog projection", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      requests.push(`${url.pathname}${url.search}`);
      return url.pathname.endsWith("/categories")
        ? success([category])
        : success({
          items: [product],
          page: 1,
          size: 20,
          total: 1,
        });
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
    expect(wrapper.findAll(".catalog-list button")).toHaveLength(1);
    expect(wrapper.text()).toContain("1 条");
    expect(wrapper.text()).toContain("商品清单");
    expect(wrapper.text()).toContain("青荷通勤袋");
    expect(wrapper.text()).toContain("随身用品");
    expect(wrapper.text()).toContain("公开投影事实");
    expect(wrapper.text()).toContain("当前页面能确认什么");
    expect(wrapper.find(".pj-surface").exists()).toBe(false);

    await wrapper.get("#catalog-keyword").setValue("青荷");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(requests).toContain(
      "/api/v1/catalog/products?page=1&size=20&keyword=%E9%9D%92%E8%8D%B7",
    );

    await wrapper.get("img").trigger("error");
    expect(wrapper.text()).toContain("无图片");

    const refresh = wrapper.findAll("button")
      .find((button) => button.text().includes("重新读取"));
    await refresh!.trigger("click");
    await flushPromises();

    expect(wrapper.find("img").exists()).toBe(true);
  });
});
