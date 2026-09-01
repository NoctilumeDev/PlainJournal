import { beforeEach, describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import MarketingWorkspaceView from "./MarketingWorkspaceView.vue";
import { useStaffSessionStore } from "../stores/session";

describe("marketing parallel command workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it("keeps recovery context, rule creation, and benefit grant as sibling regions", () => {
    const session = useStaffSessionStore();
    session.profile = {
      id: "2089000000000000001",
      email: "operator@example.com",
      displayName: "Operator",
      status: "ACTIVE",
      roles: ["OPERATOR"],
    };
    session.accessToken = "operator-token";
    session.initialized = true;

    const wrapper = mount(MarketingWorkspaceView);

    const workbench = wrapper.get(".marketing-workbench");
    expect(workbench.attributes("aria-label")).toBe("营销权益并列命令工作区");
    expect(workbench.element.children).toHaveLength(3);
    expect(wrapper.find(".marketing-scope-pane").exists()).toBe(true);
    expect(wrapper.findAll("section.marketing-section")).toHaveLength(2);
    expect(wrapper.find(".pj-surface").exists()).toBe(false);

    expect(wrapper.text()).toContain("两类命令，两种恢复方式");
    expect(wrapper.text()).toContain("创建优惠规则");
    expect(wrapper.text()).toContain("向顾客发放权益");
    expect(wrapper.get("#marketing-rule-code").element).toBeInstanceOf(HTMLInputElement);
    expect(wrapper.get("#marketing-user-id").element).toBeInstanceOf(HTMLInputElement);
    expect(wrapper.get("#marketing-grant-rule-code").element).toBeInstanceOf(HTMLInputElement);
    expect(wrapper.get("#marketing-grant-key").attributes("readonly")).toBeDefined();
  });
});
