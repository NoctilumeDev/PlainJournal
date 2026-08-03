import { createRouter, createWebHistory } from "vue-router";

import OperationsHomeView from "./views/OperationsHomeView.vue";
import CatalogReadOnlyView from "./views/CatalogReadOnlyView.vue";
import NotFoundView from "./views/NotFoundView.vue";
import StaffLoginView from "./views/StaffLoginView.vue";
import ForbiddenView from "./views/ForbiddenView.vue";
import FulfillmentWorkspaceView from "./views/FulfillmentWorkspaceView.vue";
import AfterSaleWorkspaceView from "./views/AfterSaleWorkspaceView.vue";
import InventoryWorkspaceView from "./views/InventoryWorkspaceView.vue";
import GovernanceWorkspaceView from "./views/GovernanceWorkspaceView.vue";
import MarketingWorkspaceView from "./views/MarketingWorkspaceView.vue";
import ChatWorkspaceView from "./views/ChatWorkspaceView.vue";
import ReviewWorkspaceView from "./views/ReviewWorkspaceView.vue";
import { resolveStaffRedirect } from "./navigation";
import { pinia } from "./stores/pinia";
import { hasAnyRole, useStaffSessionStore } from "./stores/session";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "operations-home",
      component: OperationsHomeView,
      meta: {
        title: "工作区｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR", "WAREHOUSE"],
      },
    },
    {
      path: "/catalog",
      name: "catalog-read-only",
      component: CatalogReadOnlyView,
      meta: {
        title: "商品目录｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR"],
      },
    },
    {
      path: "/fulfillment",
      name: "fulfillment-workspace",
      component: FulfillmentWorkspaceView,
      meta: {
        title: "履约与退货｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "WAREHOUSE"],
      },
    },
    {
      path: "/after-sales",
      name: "after-sale-workspace",
      component: AfterSaleWorkspaceView,
      meta: {
        title: "售后审核｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN"],
      },
    },
    {
      path: "/inventory",
      name: "inventory-workspace",
      component: InventoryWorkspaceView,
      meta: {
        title: "库存工作区｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "WAREHOUSE"],
      },
    },
    {
      path: "/marketing",
      name: "marketing-workspace",
      component: MarketingWorkspaceView,
      meta: {
        title: "营销权益｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR"],
      },
    },
    {
      path: "/governance",
      name: "governance-workspace",
      component: GovernanceWorkspaceView,
      meta: {
        title: "补偿与对账｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN"],
      },
    },
    {
      path: "/chat",
      name: "chat-workspace",
      component: ChatWorkspaceView,
      meta: {
        title: "客服会话｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR"],
      },
    },
    {
      path: "/chat/:conversationId",
      name: "chat-workspace-detail",
      component: ChatWorkspaceView,
      meta: {
        title: "客服会话详情｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR"],
      },
    },
    {
      path: "/reviews",
      name: "review-workspace",
      component: ReviewWorkspaceView,
      meta: {
        title: "评价治理｜素简记管理端",
        requiresAuth: true,
        roles: ["ADMIN", "OPERATOR"],
      },
    },
    {
      path: "/login",
      name: "staff-login",
      component: StaffLoginView,
      meta: { title: "员工登录｜素简记管理端", publicLayout: true },
    },
    {
      path: "/forbidden",
      name: "forbidden",
      component: ForbiddenView,
      meta: { title: "权限不足｜素简记管理端", publicLayout: true },
    },
    {
      path: "/:pathMatch(.*)*",
      component: NotFoundView,
      meta: { title: "页面不存在｜素简记管理端" },
    },
  ],
});

router.beforeEach(async (to) => {
  const session = useStaffSessionStore(pinia);
  await session.restore();
  if (to.meta.requiresAuth && !session.authenticated) {
    return session.accessDenied
      ? { name: "forbidden" }
      : {
          name: "staff-login",
          query: { redirect: to.fullPath },
        };
  }
  const requiredRoles = Array.isArray(to.meta.roles)
    ? to.meta.roles.filter((role): role is string => typeof role === "string")
    : [];
  if (
    to.meta.requiresAuth
    && session.profile
    && !hasAnyRole(session.profile.roles, requiredRoles)
  ) {
    return { name: "forbidden" };
  }
  if (to.name === "staff-login" && session.authenticated) {
    return resolveStaffRedirect(to.query.redirect);
  }
  return true;
});

router.afterEach((to) => {
  document.title = typeof to.meta.title === "string"
    ? to.meta.title
    : "素简记管理端";
});
