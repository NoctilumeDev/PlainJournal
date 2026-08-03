<script setup lang="ts">
import { computed, watch } from "vue";
import { RouterLink } from "vue-router";
import { PjPageContainer } from "@plain-journal/ui";

import {
  type AccountCartAccessContext,
  useAccountCartStore,
} from "../entities/account-cart";
import { useBagStore } from "../entities/guest-bag";
import { useSessionStore } from "../features/customer-session";

const bag = useBagStore();
const session = useSessionStore();
const accountCart = useAccountCartStore();
const accountAccess = computed<AccountCartAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const visibleBagCount = computed(() => session.authenticated
  ? accountCart.itemCount
  : bag.itemCount);
const bagLabel = computed(() => visibleBagCount.value > 0
  ? `购物袋 ${visibleBagCount.value}`
  : "购物袋");

watch(
  () => [
    session.authenticated,
    session.profile?.id ?? null,
    session.accessToken,
    session.bagMergeStatus,
  ] as const,
  async ([authenticated, , , mergeStatus]) => {
    if (!authenticated) {
      await accountCart.load(accountAccess.value);
      return;
    }
    if (!["idle", "succeeded", "unknown", "failed", "ownership-conflict"].includes(mergeStatus)) {
      return;
    }
    try {
      await accountCart.load(accountAccess.value, {
        force: mergeStatus === "succeeded" || mergeStatus === "unknown",
      });
    } catch {
      // The bag page owns the detailed retry state.
    }
  },
  { immediate: true },
);
</script>

<template>
  <header class="storefront-header">
    <PjPageContainer class="storefront-header__inner">
      <RouterLink class="brand-link" to="/" aria-label="素简记首页">
        <span>素简记</span>
        <small>Plain Journal</small>
      </RouterLink>
      <nav class="header-actions" aria-label="全局入口">
        <RouterLink to="/search">查找</RouterLink>
        <RouterLink to="/bag">{{ bagLabel }}</RouterLink>
        <RouterLink v-if="session.authenticated" to="/account">账户</RouterLink>
        <RouterLink v-else to="/login">登录</RouterLink>
        <RouterLink to="/index">索引</RouterLink>
      </nav>
    </PjPageContainer>
  </header>
</template>
