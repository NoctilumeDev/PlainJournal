import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import type { ReviewReport } from "@plain-journal/foundation";

import ReviewWorkspaceView from "./ReviewWorkspaceView.vue";
import { useStaffSessionStore } from "../stores/session";

const REPORT_ID = "2087000000000000101";

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

function reportFixture(overrides: Partial<ReviewReport> = {}): ReviewReport {
  return {
    id: REPORT_ID,
    reviewId: "2087000000000000201",
    productId: "2087000000000000301",
    rating: 2,
    reviewContent: "商品颜色与订单页展示存在差异，需要平台核对。",
    reasonCode: "FALSE_INFORMATION",
    detail: "举报人指出评价内容可能混淆了两个不同规格。",
    status: "OPEN",
    resolution: null,
    createdAt: "2026-09-01T00:00:00Z",
    resolvedAt: null,
    ...overrides,
  };
}

function reportsPage(items: ReviewReport[]) {
  return { items, page: 1, size: 100, total: items.length };
}

function mountWorkspace() {
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
  return mount(ReviewWorkspaceView);
}

describe("review governance split workbench", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("separates queue scanning from evidence and eligible commands", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success(reportsPage([reportFixture()])))
      .mockResolvedValueOnce(success(reportsPage([reportFixture({
        status: "RESOLVED",
        resolution: "REJECTED",
        resolvedAt: "2026-09-01T01:00:00Z",
      })]))));

    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.find(".split-workbench__rail").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__queue").exists()).toBe(true);
    expect(wrapper.find(".split-workbench__detail").exists()).toBe(true);
    expect(wrapper.findAll(".review-queue__list button")).toHaveLength(1);
    expect(wrapper.text()).toContain("公开内容与举报证据");
    expect(wrapper.text()).toContain("商品颜色与订单页展示存在差异");
    expect(wrapper.findAll(".review-action")).toHaveLength(2);
    expect(wrapper.text()).toContain("保存平台回复");
    expect(wrapper.text()).toContain("提交审核结论");

    const statusButtons = wrapper.findAll(".review-status-nav button");
    await statusButtons[1]!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("已完成审核");
    expect(wrapper.findAll(".review-action")).toHaveLength(0);
  });
});
