<script setup lang="ts">
import { RouterView } from "vue-router";

import AppFooter from "./AppFooter.vue";
import AppHeader from "./AppHeader.vue";
</script>

<template>
  <a class="pj-skip-link" href="#main-content">跳到主要内容</a>
  <div class="pj-app-shell storefront-shell">
    <AppHeader />
    <main id="main-content" class="pj-app-main" tabindex="-1">
      <RouterView />
    </main>
    <AppFooter />
  </div>
</template>

<style src="./app-shell.css"></style>
