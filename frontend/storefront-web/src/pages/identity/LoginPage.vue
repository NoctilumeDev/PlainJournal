<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

import { AuthenticationPanel } from "../../features/customer-session";
import { safeReturnTo } from "../../shared/lib";

const route = useRoute();
const router = useRouter();
const returnTo = computed(() => safeReturnTo(route.query.returnTo));

async function completeAuthentication() {
  await router.replace(returnTo.value);
}
</script>

<template>
  <AuthenticationPanel
    mode="login"
    :return-to="returnTo"
    @authenticated="completeAuthentication"
  />
</template>
