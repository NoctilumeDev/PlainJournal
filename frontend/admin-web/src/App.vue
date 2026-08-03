<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, RouterView } from "vue-router";
import { useRoute, useRouter } from "vue-router";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

import { useStaffSessionStore } from "./stores/session";

const route = useRoute();
const router = useRouter();
const session = useStaffSessionStore();
const publicLayout = computed(() => route.meta.publicLayout === true);
const roles = computed(() => session.profile?.roles ?? []);
const canCatalog = computed(() => roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)));
const canWarehouse = computed(() => roles.value.some((role) => ["ADMIN", "WAREHOUSE"].includes(role)));
const isAdmin = computed(() => roles.value.includes("ADMIN"));

async function logout() {
  try {
    await session.logout();
    await router.replace("/login");
  } catch {
    // Preserve the session until an explicit local-only choice is made.
  }
}

async function clearLocal() {
  session.clearLocalOnly();
  await router.replace("/login");
}
</script>

<template>
  <a class="pj-skip-link" href="#admin-main">跳到主要内容</a>
  <main v-if="publicLayout" id="admin-main" class="pj-app-main" tabindex="-1">
    <RouterView />
  </main>
  <div v-else class="pj-app-shell admin-shell">
    <header class="admin-header">
      <PjPageContainer class="admin-header__inner">
        <RouterLink class="admin-brand" to="/">
          <span>素简记</span>
          <small>管理端</small>
        </RouterLink>
        <div class="admin-session">
          <span>{{ session.profile?.displayName }}</span>
          <small>{{ session.profile?.roles.join(" / ") }}</small>
          <PjButton variant="text" :disabled="session.busy" @click="logout">退出</PjButton>
        </div>
      </PjPageContainer>
    </header>
    <PjStatusNotice
      v-if="session.logoutError"
      class="admin-session-warning"
      tone="warning"
      title="退出尚未确认"
      assertive
    >
      <span>{{ session.logoutError }} 当前会话仍保留。</span>
      <template #actions>
        <PjButton variant="text" @click="clearLocal">仅清除此设备</PjButton>
      </template>
    </PjStatusNotice>
    <div class="admin-layout">
      <nav class="admin-nav" aria-label="管理端导航">
        <RouterLink to="/">工作区</RouterLink>
        <RouterLink v-if="canCatalog" to="/catalog">商品目录</RouterLink>
        <RouterLink v-if="canWarehouse" to="/inventory">库存</RouterLink>
        <RouterLink v-if="canWarehouse" to="/fulfillment">履约与退货</RouterLink>
        <RouterLink v-if="isAdmin" to="/after-sales">售后审核</RouterLink>
        <RouterLink v-if="canCatalog" to="/marketing">营销权益</RouterLink>
        <RouterLink v-if="canCatalog" to="/chat">客服会话</RouterLink>
        <RouterLink v-if="canCatalog" to="/reviews">评价治理</RouterLink>
        <RouterLink v-if="isAdmin" to="/governance">补偿与对账</RouterLink>
      </nav>
      <main id="admin-main" class="pj-app-main" tabindex="-1">
        <RouterView />
      </main>
    </div>
  </div>
</template>
