<script setup lang="ts">
import { watch } from "vue";
import { RouterView } from "vue-router";
import { useRoute, useRouter } from "vue-router";

import { useSessionStore } from "../features/customer-session";
import AppFooter from "./AppFooter.vue";
import AppHeader from "./AppHeader.vue";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();

watch(() => session.reauthRequired, (required) => {
  if (required && route.name !== "login" && route.name !== "register") {
    void router.replace({
      name: "login",
      query: { returnTo: route.fullPath },
    });
  }
});
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
