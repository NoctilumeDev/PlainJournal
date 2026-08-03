import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

import AuthenticationPanel from "./AuthenticationPanel.vue";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-28T00:00:00Z",
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

function mountPanel(mode: "login" | "register") {
  const pinia = createPinia();
  setActivePinia(pinia);
  return mount(AuthenticationPanel, {
    props: {
      mode,
      returnTo: "/products/2079000000000000001",
    },
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: true,
      },
    },
  });
}

describe("customer authentication panel", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("emits completion only after login and profile establishment", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile));
    vi.stubGlobal("fetch", fetchMock);
    const wrapper = mountPanel("login");

    await wrapper.get('input[type="email"]').setValue(profile.email);
    await wrapper.get('input[type="password"]').setValue("ReaderPass123");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.emitted("authenticated")).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token");
  });

  it("registers first and establishes the session with the same credentials", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success(profile))
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile));
    vi.stubGlobal("fetch", fetchMock);
    const wrapper = mountPanel("register");

    await wrapper.get('input[autocomplete="name"]').setValue("Reader");
    await wrapper.get('input[type="email"]').setValue(profile.email);
    await wrapper.get('input[type="password"]').setValue("ReaderPass123");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.emitted("authenticated")).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      displayName: "Reader",
      email: profile.email,
      password: "ReaderPass123",
    });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      email: profile.email,
      password: "ReaderPass123",
    });
  });

  it("shows a failed login as an assertive danger notice without completing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(failure(
      401,
      "INVALID_CREDENTIALS",
      "邮箱或密码不正确",
    )));
    const wrapper = mountPanel("login");

    await wrapper.get('input[type="email"]').setValue(profile.email);
    await wrapper.get('input[type="password"]').setValue("WrongPass123");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const notice = wrapper.get(".pj-status-notice--danger");
    expect(notice.attributes("role")).toBe("alert");
    expect(notice.text()).toContain("登录未完成");
    expect(notice.text()).toContain("邮箱或密码不正确");
    expect(wrapper.emitted("authenticated")).toBeUndefined();
  });

  it("does not emit completion when registration fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(failure(
      409,
      "EMAIL_ALREADY_EXISTS",
      "该邮箱已注册",
    )));
    const wrapper = mountPanel("register");

    await wrapper.get('input[autocomplete="name"]').setValue("Reader");
    await wrapper.get('input[type="email"]').setValue(profile.email);
    await wrapper.get('input[type="password"]').setValue("ReaderPass123");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.get(".pj-status-notice--danger").text())
      .toContain("账户未创建");
    expect(wrapper.emitted("authenticated")).toBeUndefined();
  });
});
