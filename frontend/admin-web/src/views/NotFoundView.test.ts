import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import NotFoundView from "./NotFoundView.vue";

describe("admin not-found state", () => {
  it("explains the missing route and offers one safe return", () => {
    const wrapper = mount(NotFoundView, {
      global: {
        stubs: {
          RouterLink: {
            props: ["to"],
            template: '<a :href="to"><slot /></a>',
          },
        },
      },
    });

    expect(wrapper.get(".eyebrow").text()).toBe("页面不存在");
    expect(wrapper.get("h1").text()).toBe("这里没有可进入的管理页面。");
    expect(wrapper.text()).toContain("系统仍会按当前角色收窄");
    expect(wrapper.get("a").attributes("href")).toBe("/");
    expect(wrapper.findAll(".admin-primary-link")).toHaveLength(1);
  });
});
