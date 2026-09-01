<script setup lang="ts">
import { RouterLink } from "vue-router";
</script>

<template>
  <section class="admin-auth-page">
    <div class="admin-auth-panel">
      <p class="eyebrow">页面不存在</p>
      <h1>这里没有可进入的管理页面。</h1>
      <p>
        地址可能已经失效，或这个管理入口尚未开放。
        返回工作区后，系统仍会按当前角色收窄可见任务与操作权限。
      </p>
      <RouterLink class="admin-primary-link" to="/">
        返回有权限的工作区 →
      </RouterLink>
    </div>
  </section>
</template>
