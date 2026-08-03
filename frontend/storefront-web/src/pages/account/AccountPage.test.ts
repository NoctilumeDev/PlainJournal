import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  type BagMergeStatus,
  useSessionStore,
} from "../../features/customer-session";
import AccountPage from "./AccountPage.vue";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-02T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-08-02T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const tokens = {
  tokenType: "Bearer",
  accessToken: "access-token",
  expiresIn: 900,
  refreshToken: "refresh-token",
};

const profile = {
  id: "2079000000000000999",
  email: "reader@example.com",
  displayName: "Reader",
  status: "ACTIVE",
  roles: ["CUSTOMER"],
};

async function mountAccount() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div>home</div>" } },
      { path: "/account", component: AccountPage },
      { path: "/account/addresses", component: { template: "<div />" } },
      { path: "/orders", component: { template: "<div />" } },
      { path: "/after-sales", component: { template: "<div />" } },
      { path: "/account/benefits", component: { template: "<div />" } },
      { path: "/account/notifications", component: { template: "<div />" } },
      { path: "/support", component: { template: "<div />" } },
      { path: "/bag", component: { template: "<div />" } },
      { path: "/checkout", component: { template: "<div />" } },
    ],
  });
  await router.push("/account");
  await router.isReady();
  const session = useSessionStore();
  const wrapper = mount(AccountPage, {
    global: { plugins: [pinia, router] },
  });
  return { router, session, wrapper };
}

describe("AccountPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it.each([
    ["pending", "processing", "正在合并购物袋"],
    ["succeeded", "success", "购物袋已合并"],
    ["unknown", "unknown", "合并结果待确认"],
    ["failed", "danger", "购物袋合并未完成"],
    ["ownership-conflict", "attention", "需要先核对设备上的待确认合并"],
  ] satisfies Array<[BagMergeStatus, string, string]>)(
    "renders %s merge facts with the %s semantic tone",
    async (status, tone, title) => {
      const { session, wrapper } = await mountAccount();
      session.profile = profile;
      session.accessToken = "access-token";
      session.bagMergeStatus = status;
      session.bagMergeMessage = `merge-${status}`;
      await flushPromises();

      const notice = wrapper.get(`.account-merge-notice.pj-status-notice--${tone}`);
      expect(notice.text()).toContain(title);
      expect(notice.text()).toContain(`merge-${status}`);
    },
  );

  it("keeps the account after an unknown logout until local clearing is explicit", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "退出结果暂时未知",
      ));
    vi.stubGlobal("fetch", fetchMock);
    const { router, session, wrapper } = await mountAccount();
    await session.login({
      email: profile.email,
      password: "ReaderPass123",
    });
    await flushPromises();

    const logoutButton = wrapper.findAll("button")
      .find((button) => button.text().trim() === "退出");
    expect(logoutButton).toBeDefined();
    await logoutButton?.trigger("click");
    await flushPromises();

    const notice = wrapper.get(
      ".account-logout-notice.pj-status-notice--unknown",
    );
    expect(notice.text()).toContain("服务端退出结果待确认");
    expect(session.authenticated).toBe(true);
    expect(router.currentRoute.value.path).toBe("/account");
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token");

    const clearButton = wrapper.findAll("button")
      .find((button) => button.text().includes("仅清除此设备"));
    expect(clearButton).toBeDefined();
    await clearButton?.trigger("click");
    await flushPromises();

    expect(session.authenticated).toBe(false);
    expect(router.currentRoute.value.path).toBe("/");
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBeNull();
  });
});
