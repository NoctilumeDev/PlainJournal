import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";

import ChatWorkspaceView from "./ChatWorkspaceView.vue";
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

describe("chat three-region workspace", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("keeps scope, queue, and private thread as sibling regions", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([])));
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: "/chat",
          name: "chat-workspace",
          component: ChatWorkspaceView,
        },
        {
          path: "/chat/:conversationId",
          name: "chat-workspace-detail",
          component: ChatWorkspaceView,
        },
      ],
    });
    await router.push("/chat");
    await router.isReady();

    const session = useStaffSessionStore(pinia);
    session.profile = {
      id: "2089000000000000001",
      email: "operator@example.com",
      displayName: "Operator",
      status: "ACTIVE",
      roles: ["OPERATOR"],
    };
    session.accessToken = "operator-token";
    session.initialized = true;

    const wrapper = mount(ChatWorkspaceView, {
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const workbench = wrapper.get(".chat-workbench");
    expect(workbench.attributes("aria-label")).toBe("客服会话工作台");
    expect(workbench.element.children).toHaveLength(3);
    expect(wrapper.find(".chat-scope-pane").exists()).toBe(true);
    expect(wrapper.find("aside.chat-queue").exists()).toBe(true);
    expect(wrapper.find("section.chat-thread").exists()).toBe(true);
    expect(wrapper.find(".pj-surface").exists()).toBe(false);
    expect(wrapper.text()).toContain("REST 确认，实时提示");
    expect(wrapper.text()).toContain("队列摘要与私聊正文保持分离");

    wrapper.unmount();
  });
});
