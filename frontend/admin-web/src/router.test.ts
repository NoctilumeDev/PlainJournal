import { beforeEach, describe, expect, it } from "vitest";

import { router } from "./router";
import { pinia } from "./stores/pinia";
import { useStaffSessionStore } from "./stores/session";

const OPERATOR_PROFILE = {
  id: "2088000000000000001",
  email: "operator@example.com",
  displayName: "Operator",
  status: "ACTIVE",
  roles: ["OPERATOR"],
};

describe("admin router guard", () => {
  const session = useStaffSessionStore(pinia);

  beforeEach(async () => {
    localStorage.clear();
    session.clearLocalOnly();
    session.profile = null;
    session.accessToken = null;
    session.initialized = true;
    session.accessDenied = null;
    await router.replace("/login");
  });

  it("redirects an anonymous staff request back through login", async () => {
    await router.push("/inventory");

    expect(router.currentRoute.value.name).toBe("staff-login");
    expect(router.currentRoute.value.query.redirect).toBe("/inventory");
    expect(document.title).toBe("员工登录｜素简记管理端");
  });

  it("routes a rejected customer identity to the explicit forbidden page", async () => {
    session.accessDenied = {
      email: "customer@example.com",
      roles: ["CUSTOMER"],
      remoteLogoutConfirmed: true,
    };

    await router.push("/catalog");

    expect(router.currentRoute.value.name).toBe("forbidden");
  });

  it("enforces workspace-specific roles after authentication", async () => {
    session.profile = OPERATOR_PROFILE;
    session.accessToken = "operator-token";

    await router.push("/inventory");

    expect(router.currentRoute.value.name).toBe("forbidden");
  });

  it("honors only an internal post-login redirect for authorized staff", async () => {
    session.profile = OPERATOR_PROFILE;
    session.accessToken = "operator-token";

    await router.push("/login?redirect=/chat/2088000000000000101");

    expect(router.currentRoute.value.fullPath)
      .toBe("/chat/2088000000000000101");
    expect(document.title).toBe("客服会话详情｜素简记管理端");
  });
});
