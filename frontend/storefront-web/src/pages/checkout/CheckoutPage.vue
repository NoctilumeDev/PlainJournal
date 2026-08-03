<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, useRouter } from "vue-router";

import { PjPageContainer } from "@plain-journal/ui";

import {
  CheckoutWorkspace,
  type CheckoutAccessContext,
} from "../../features/checkout";
import { useSessionStore } from "../../features/customer-session";

const session = useSessionStore();
const router = useRouter();
const checkoutAccess = computed<CheckoutAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

async function openOrder(orderNo: string) {
  await router.push({
    name: "order-detail",
    params: { orderNo },
  });
}
</script>

<template>
  <PjPageContainer as="section" class="checkout-page-shell">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/bag">购物袋</RouterLink>
      <span aria-hidden="true">/</span>
      <span>订单确认</span>
    </nav>

    <header class="checkout-intro">
      <div class="checkout-intro__copy">
        <p>提交前核对</p>
        <h1>订单确认</h1>
        <p>
          地址、商品和优惠先组成草稿；实时价格、库存与权益资格核对完成后才能提交。
        </p>
      </div>
      <p class="checkout-intro__sequence">
        <strong>草稿</strong>
        <span aria-hidden="true">→</span>
        <strong>权威核对</strong>
        <span aria-hidden="true">→</span>
        <strong>订单事实</strong>
      </p>
    </header>

    <CheckoutWorkspace
      :access="checkoutAccess"
      @order-confirmed="openOrder"
    />
  </PjPageContainer>
</template>

<style scoped>
.checkout-page-shell {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.checkout-intro {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--pj-space-7);
  margin-bottom: var(--pj-space-7);
}

.checkout-intro__copy {
  max-width: var(--pj-layout-reading);
}

.checkout-intro__copy > p:first-child {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.checkout-intro h1 {
  margin: 0;
  font-size: clamp(2.4rem, 5vw, 5rem);
  font-weight: 520;
  letter-spacing: -0.055em;
  line-height: 0.98;
}

.checkout-intro__copy > p:last-child {
  margin: var(--pj-space-4) 0 0;
  color: var(--pj-text-secondary);
}

.checkout-intro__sequence {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--pj-space-2);
  max-width: 24rem;
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.checkout-intro__sequence strong {
  color: var(--pj-text-primary);
  font-weight: 550;
}

@media (max-width: 48rem) {
  .checkout-intro {
    grid-template-columns: 1fr;
    align-items: start;
    gap: var(--pj-space-4);
  }

  .checkout-intro__sequence {
    justify-content: flex-start;
  }
}
</style>
