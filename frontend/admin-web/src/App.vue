<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink, RouterView } from "vue-router";
import { useRoute, useRouter } from "vue-router";
import {
  PjButton,
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
const workspaceMenu = ref<HTMLDetailsElement | null>(null);
const workspaceLinks = computed(() => [
  { to: "/", label: "工作区总览", visible: true },
  { to: "/catalog", label: "商品目录", visible: canCatalog.value },
  { to: "/inventory", label: "库存", visible: canWarehouse.value },
  { to: "/fulfillment", label: "履约与退货", visible: canWarehouse.value },
  { to: "/after-sales", label: "售后审核", visible: isAdmin.value },
  { to: "/marketing", label: "营销权益", visible: canCatalog.value },
  { to: "/chat", label: "客服会话", visible: canCatalog.value },
  { to: "/reviews", label: "评价治理", visible: canCatalog.value },
  { to: "/governance", label: "补偿与对账", visible: isAdmin.value },
].filter((item) => item.visible));
const currentWorkspace = computed(() => {
  const matched = workspaceLinks.value.find((item) => {
    if (item.to === "/") {
      return route.path === "/";
    }
    return route.path === item.to || route.path.startsWith(`${item.to}/`);
  });
  return matched?.label ?? "管理工作区";
});

function closeWorkspaceMenu() {
  if (workspaceMenu.value) {
    workspaceMenu.value.open = false;
  }
}

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
      <div class="admin-header__inner">
        <RouterLink class="admin-brand" to="/">
          <span>素简记</span>
          <small>PLAIN JOURNAL</small>
        </RouterLink>
        <details ref="workspaceMenu" class="admin-workspace-switcher">
          <summary>
            <span>当前工作区</span>
            <strong>{{ currentWorkspace }}</strong>
          </summary>
          <nav aria-label="管理工作区切换">
            <RouterLink
              v-for="item in workspaceLinks"
              :key="item.to"
              :to="item.to"
              @click="closeWorkspaceMenu"
            >
              {{ item.label }}
            </RouterLink>
          </nav>
        </details>
        <div class="admin-session">
          <span>{{ session.profile?.displayName }}</span>
          <small>{{ session.profile?.roles.join(" / ") }}</small>
          <PjButton variant="text" :disabled="session.busy" @click="logout">退出</PjButton>
        </div>
      </div>
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
    <main id="admin-main" class="pj-app-main admin-main" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>
