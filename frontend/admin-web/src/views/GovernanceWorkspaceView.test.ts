import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import GovernanceWorkspaceView from "./GovernanceWorkspaceView.vue";
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

describe("governance parallel task workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("separates read-only reconciliation from the selected compensation command", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([])));
    const session = useStaffSessionStore();
    session.profile = {
      id: "2089000000000000001",
      email: "admin@example.com",
      displayName: "Admin",
      status: "ACTIVE",
      roles: ["ADMIN"],
    };
    session.accessToken = "admin-token";
    session.initialized = true;

    const wrapper = mount(GovernanceWorkspaceView);
    await flushPromises();

    const workbench = wrapper.get(".governance-workbench");
    expect(workbench.attributes("aria-label")).toBe("补偿与对账治理工作区");
    expect(workbench.element.children).toHaveLength(3);
    expect(wrapper.findAll(".governance-domain")).toHaveLength(4);
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(2);
    expect(wrapper.find(".pj-surface").exists()).toBe(false);

    expect(wrapper.find("#refund-retry-no").exists()).toBe(true);
    expect(wrapper.find("#payment-exception-no").exists()).toBe(false);

    await wrapper.get('[role="tab"][aria-selected="false"]').trigger("click");

    expect(wrapper.find("#refund-retry-no").exists()).toBe(false);
    expect(wrapper.find("#payment-exception-no").exists()).toBe(true);
    expect(wrapper.text()).toContain("Trade 权威状态 · Payment 退款事实");
  });
});
