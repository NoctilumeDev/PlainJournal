<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { PjPageContainer } from "@plain-journal/ui";

import type { AfterSaleAccessContext } from "../entities/after-sale";
import { AfterSaleWorkspace } from "../features/after-sale-workflow";
import { useSessionStore } from "../features/customer-session";

const route = useRoute();
const session = useSessionStore();
const afterSaleNo = computed(() => String(route.params.afterSaleNo ?? ""));
const access = computed<AfterSaleAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
</script>

<template>
  <PjPageContainer as="section" class="after-sale-detail-page">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/after-sales">售后服务</RouterLink>
      <span aria-hidden="true">/</span>
      <span>售后详情</span>
    </nav>

    <header class="after-sale-page-header">
      <div>
        <p>售后 {{ afterSaleNo }}</p>
        <h1>售后详情</h1>
      </div>
      <RouterLink class="text-action" to="/after-sales">返回售后列表</RouterLink>
    </header>

    <AfterSaleWorkspace :after-sale-no="afterSaleNo" :access="access" />
  </PjPageContainer>
</template>

<style scoped>
.after-sale-detail-page {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.after-sale-page-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.after-sale-page-header p {
  margin: 0 0 var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
  overflow-wrap: anywhere;
}

.after-sale-page-header h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
}

@media (max-width: 48rem) {
  .after-sale-page-header {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 32rem) {
  .after-sale-detail-page {
    padding-top: var(--pj-space-5);
  }
}
</style>
