import { beforeEach, describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";

import App from "./App.vue";
import { useStaffSessionStore } from "./stores/session";

const HomeStub = { template: "<p>workspace home</p>" };
const LoginStub = { template: "<p>staff login</p>" };

async function mountApp(path: string, roles: string[] = []) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: HomeStub },
      {
        path: "/login",
        component: LoginStub,
        meta: { publicLayout: true },
      },
    ],
  });
  const session = useStaffSessionStore(pinia);
  session.initialized = true;
  if (roles.length > 0) {
    session.profile = {
      id: "2088000000000000003",
      email: "admin@example.com",
      displayName: "Admin",
      status: "ACTIVE",
      roles,
    };
    session.accessToken = "admin-token";
  }
  await router.push(path);
  await router.isReady();
  const wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
    },
  });
  return { router, session, wrapper };
}

describe("admin application shell", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("renders the complete administrator navigation and confirms local logout", async () => {
    const { router, session, wrapper } = await mountApp("/", ["ADMIN"]);

    expect(wrapper.text()).toContain("Admin");
    expect(wrapper.text()).toContain("售后审核");
    expect(wrapper.text()).toContain("补偿与对账");

    await wrapper.get("button").trigger("click");
    await flushPromises();

    expect(session.authenticated).toBe(false);
    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("offers an explicit local-only recovery when remote logout is unknown", async () => {
    const { router, session, wrapper } = await mountApp("/", ["OPERATOR"]);
    session.logoutError = "服务端退出结果未知";
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain("当前会话仍保留");
    const recovery = wrapper.findAll("button")
      .find((button) => button.text().includes("仅清除此设备"));
    expect(recovery).toBeDefined();
    await recovery!.trigger("click");
    await flushPromises();

    expect(session.authenticated).toBe(false);
    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("uses the public layout for the staff login route", async () => {
    const { wrapper } = await mountApp("/login");

    expect(wrapper.text()).toContain("staff login");
    expect(wrapper.text()).not.toContain("商品目录");
  });
});
