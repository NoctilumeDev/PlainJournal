import { beforeEach, describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";

import { THEME_STORAGE_KEY } from "../features/theme";
import GlobalIndexView from "./GlobalIndexView.vue";

describe("global index theme preference", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute("data-pj-theme");
  });

  it("switches the complete storefront theme from the global index", async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div>home</div>" } },
        { path: "/index", component: GlobalIndexView },
        {
          path: "/products",
          name: "products",
          component: { template: "<div>products</div>" },
        },
        { path: "/search", name: "search", component: { template: "<div>search</div>" } },
        { path: "/bag", component: { template: "<div>bag</div>" } },
      ],
    });
    await router.push("/index");
    await router.isReady();

    const wrapper = mount(GlobalIndexView, {
      global: { plugins: [pinia, router] },
    });
    const choices = wrapper.findAll<HTMLInputElement>('input[name="storefront-theme"]');

    expect(wrapper.text()).toContain("页面气质");
    expect(wrapper.text()).not.toContain("内容待运营补齐");
    expect(wrapper.findAll("h2").map((heading) => heading.text())).toEqual([
      "找商品",
      "账户事务",
      "售后与支持",
      "页面气质",
    ]);
    expect(wrapper.findAll("a").map((link) => link.attributes("href")))
      .toEqual(expect.arrayContaining([
        "/products",
        "/products?category=carry",
        "/products?category=writing",
        "/search?q=%E9%80%9A%E5%8B%A4",
        "/account/notifications",
        "/after-sales",
        "/support",
      ]));
    expect(choices).toHaveLength(2);
    expect(choices[1]?.element.checked).toBe(true);

    await choices[0]!.setValue(true);

    expect(document.documentElement.dataset.pjTheme).toBe("subai");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("subai");
  });
});
