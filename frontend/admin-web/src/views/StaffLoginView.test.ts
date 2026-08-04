import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";

import StaffLoginView from "./StaffLoginView.vue";
import { useStaffSessionStore } from "../stores/session";

async function mountLogin(redirect = "") {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<p>home</p>" } },
      { path: "/chat/:conversationId", component: { template: "<p>chat</p>" } },
      { path: "/forbidden", component: { template: "<p>forbidden</p>" } },
      { path: "/login", component: StaffLoginView },
    ],
  });
  await router.push(redirect ? `/login?redirect=${encodeURIComponent(redirect)}` : "/login");
  await router.isReady();
  const session = useStaffSessionStore(pinia);
  const wrapper = mount(StaffLoginView, {
    global: {
      plugins: [pinia, router],
    },
  });
  await wrapper.get("#staff-email").setValue("operator@example.com");
  await wrapper.get("#staff-password").setValue("Strong-password-1");
  return { router, session, wrapper };
}

describe("staff login view", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("continues to a bounded internal workspace after staff authentication", async () => {
    const { router, session, wrapper } = await mountLogin("/chat/2088000000000000101");
    const login = vi.spyOn(session, "login").mockResolvedValue(true);

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(login).toHaveBeenCalledWith({
      email: "operator@example.com",
      password: "Strong-password-1",
    });
    expect(router.currentRoute.value.path).toBe("/chat/2088000000000000101");
  });

  it("moves an authenticated non-staff identity to the forbidden page", async () => {
    const { router, session, wrapper } = await mountLogin();
    vi.spyOn(session, "login").mockResolvedValue(false);

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/forbidden");
  });

  it("keeps the current route when identity verification fails", async () => {
    const { router, session, wrapper } = await mountLogin();
    session.error = "身份服务暂时不可用";
    vi.spyOn(session, "login").mockRejectedValue(new Error("network unavailable"));

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/login");
    expect(wrapper.text()).toContain("身份服务暂时不可用");
  });
});
