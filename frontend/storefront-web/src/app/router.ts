import { createRouter, createWebHistory } from "vue-router";

import ProductListPage from "../pages/catalog/ProductListPage.vue";
import HomePage from "../pages/home/HomePage.vue";
import SearchPage from "../pages/search/SearchPage.vue";
import BagPage from "../pages/bag/BagPage.vue";
import ProductDetailView from "../views/ProductDetailView.vue";
import GlobalIndexView from "../views/GlobalIndexView.vue";
import LoginPage from "../pages/identity/LoginPage.vue";
import RegisterPage from "../pages/identity/RegisterPage.vue";
import AccountPage from "../pages/account/AccountPage.vue";
import AddressManagementPage from "../pages/account/AddressManagementPage.vue";
import NotificationCenterPage from "../pages/account/NotificationCenterPage.vue";
import CheckoutPage from "../pages/checkout/CheckoutPage.vue";
import OrderListPage from "../pages/orders/OrderListPage.vue";
import OrderDetailView from "../views/OrderDetailView.vue";
import AfterSaleListView from "../views/AfterSaleListView.vue";
import AfterSaleDetailView from "../views/AfterSaleDetailView.vue";
import BenefitCenterView from "../views/BenefitCenterView.vue";
import SupportChatView from "../views/SupportChatView.vue";
import NotFoundView from "../views/NotFoundView.vue";
import { useSessionStore } from "../features/customer-session";
import { pinia } from "./pinia";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "home",
      component: HomePage,
      meta: { title: "素简记｜精选日常用品" },
    },
    {
      path: "/products",
      name: "products",
      component: ProductListPage,
      meta: { title: "全部商品｜素简记" },
    },
    {
      path: "/products/:productId",
      name: "product-detail",
      component: ProductDetailView,
      meta: { title: "商品详情｜素简记" },
    },
    {
      path: "/search",
      name: "search",
      component: SearchPage,
      meta: { title: "查找｜素简记" },
    },
    {
      path: "/index",
      name: "global-index",
      component: GlobalIndexView,
      meta: { title: "索引｜素简记" },
    },
    {
      path: "/bag",
      name: "bag",
      component: BagPage,
      meta: { title: "购物袋｜素简记" },
    },
    {
      path: "/login",
      name: "login",
      component: LoginPage,
      meta: { title: "登录｜素简记" },
    },
    {
      path: "/register",
      name: "register",
      component: RegisterPage,
      meta: { title: "注册｜素简记" },
    },
    {
      path: "/account",
      name: "account",
      component: AccountPage,
      meta: { title: "账户｜素简记", requiresAuth: true },
    },
    {
      path: "/account/addresses",
      name: "account-addresses",
      component: AddressManagementPage,
      meta: { title: "收货信息｜素简记", requiresAuth: true },
    },
    {
      path: "/checkout",
      name: "checkout",
      component: CheckoutPage,
      meta: { title: "订单确认｜素简记", requiresAuth: true },
    },
    {
      path: "/orders",
      name: "orders",
      component: OrderListPage,
      meta: { title: "我的订单｜素简记", requiresAuth: true },
    },
    {
      path: "/orders/:orderNo",
      name: "order-detail",
      component: OrderDetailView,
      meta: { title: "订单结果｜素简记", requiresAuth: true },
    },
    {
      path: "/after-sales",
      name: "after-sales",
      component: AfterSaleListView,
      meta: { title: "售后服务｜素简记", requiresAuth: true },
    },
    {
      path: "/after-sales/:afterSaleNo",
      name: "after-sale-detail",
      component: AfterSaleDetailView,
      meta: { title: "售后详情｜素简记", requiresAuth: true },
    },
    {
      path: "/account/benefits",
      name: "account-benefits",
      component: BenefitCenterView,
      meta: { title: "优惠权益｜素简记", requiresAuth: true },
    },
    {
      path: "/account/notifications",
      name: "account-notifications",
      component: NotificationCenterPage,
      meta: { title: "通知｜素简记", requiresAuth: true },
    },
    {
      path: "/support",
      name: "support-chat",
      component: SupportChatView,
      meta: { title: "联系素简记｜素简记", requiresAuth: true },
    },
    {
      path: "/support/:conversationId",
      name: "support-chat-detail",
      component: SupportChatView,
      meta: { title: "会话详情｜素简记", requiresAuth: true },
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: NotFoundView,
      meta: { title: "页面不存在｜素简记" },
    },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    if (to.hash) {
      return { el: to.hash, behavior: "smooth" };
    }
    if (to.fullPath !== from.fullPath) {
      return { top: 0 };
    }
    return undefined;
  },
});

router.beforeEach(async (to) => {
  const session = useSessionStore(pinia);
  await session.restore();
  if (to.meta.requiresAuth && !session.authenticated) {
    return {
      name: "login",
      query: { returnTo: to.fullPath },
    };
  }
  if ((to.name === "login" || to.name === "register") && session.authenticated) {
    return { name: "account" };
  }
  return true;
});

router.afterEach((to) => {
  document.title = typeof to.meta.title === "string"
    ? to.meta.title
    : "素简记";
});
