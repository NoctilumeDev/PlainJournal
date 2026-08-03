<script setup lang="ts">
import { RouterLink } from "vue-router";

import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
</script>

<template>
  <section class="admin-auth-page">
    <div class="admin-auth-panel">
      <p class="eyebrow">权限不足</p>
      <h1>{{ session.authenticated ? "当前角色不能进入这个工作区。" : "这个账户不能进入管理端。" }}</h1>
      <p v-if="session.accessDenied">
        已验证 {{ session.accessDenied.email }}，角色为
        {{ session.accessDenied.roles.join(" / ") || "无" }}。
      </p>
      <p>
        管理权限由 Identity 服务端角色和各服务安全规则共同决定，前端隐藏导航不能代替后端授权。
      </p>
      <p v-if="session.accessDenied && !session.accessDenied.remoteLogoutConfirmed">
        服务端退出确认未返回，但本设备上的管理端令牌已经清除。
      </p>
      <RouterLink v-if="session.authenticated" class="admin-primary-link" to="/">
        返回有权限的工作区 →
      </RouterLink>
      <RouterLink v-else class="admin-primary-link" to="/login">使用员工账户登录 →</RouterLink>
    </div>
  </section>
</template>
