import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import type { StockPosition, Warehouse } from "@plain-journal/foundation";

import InventoryWorkspaceView from "./InventoryWorkspaceView.vue";
import { useStaffSessionStore } from "../stores/session";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-09-01T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const warehouse: Warehouse = {
  id: "2089000000000003999",
  code: "HZ_MAIN",
  name: "杭州主仓",
  status: "ACTIVE",
  version: 0,
};

const stock: StockPosition = {
  warehouseId: warehouse.id,
  skuId: "2089000000000000011",
  onHand: 10,
  reserved: 2,
  available: 8,
  version: 3,
};

describe("inventory list workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps warehouse selection, authority lookup, and adjustment in one object context", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      return url.pathname.endsWith(`/stocks/${stock.skuId}`)
        ? success(stock)
        : success([warehouse]);
    }));
    const session = useStaffSessionStore();
    session.profile = {
      id: "2089000000000000001",
      email: "warehouse@example.com",
      displayName: "Warehouse",
      status: "ACTIVE",
      roles: ["WAREHOUSE"],
    };
    session.accessToken = "warehouse-token";
    session.initialized = true;

    const wrapper = mount(InventoryWorkspaceView);
    await flushPromises();

    expect(wrapper.find(".list-workbench__filters").exists()).toBe(true);
    expect(wrapper.find(".list-workbench__list").exists()).toBe(true);
    expect(wrapper.find(".list-workbench__detail").exists()).toBe(true);
    expect(wrapper.findAll(".inventory-warehouse-list button")).toHaveLength(1);
    expect(wrapper.text()).toContain("杭州主仓");
    expect(wrapper.get("#stock-warehouse-id").element).toHaveProperty("value", warehouse.id);
    expect(wrapper.get("#adjustment-warehouse-id").element).toHaveProperty("value", warehouse.id);
    expect(wrapper.find(".pj-surface").exists()).toBe(false);

    await wrapper.get("#stock-sku-id").setValue(stock.skuId);
    await wrapper.get(".inventory-form--lookup").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("在手");
    expect(wrapper.text()).toContain("10");
    expect(wrapper.text()).toContain("可用");
    expect(wrapper.text()).toContain("8");
    expect(wrapper.text()).toContain("同流水、同载荷才能安全重放");
  });
});
