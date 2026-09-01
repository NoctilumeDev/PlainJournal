<script setup lang="ts">
import { RouterLink } from "vue-router";

import { PjPageContainer } from "@plain-journal/ui";

import { ThemePreference } from "../features/theme";
import { useBagStore } from "../entities/guest-bag";

const bag = useBagStore();
</script>

<template>
  <PjPageContainer as="section" class="global-index">
    <header class="global-index__intro">
      <p>全局索引</p>
      <h1>改变方向时，再打开导航。</h1>
      <p>商品、账户和服务入口集中在这里，不让工具栏长期占据浏览空间。</p>
    </header>
    <div class="global-index__grid">
      <nav aria-labelledby="index-products-title">
        <p>01</p>
        <h2 id="index-products-title">找商品</h2>
        <RouterLink :to="{ name: 'products' }">全部商品</RouterLink>
        <RouterLink :to="{ name: 'products', query: { category: 'carry' } }">
          随身用品
        </RouterLink>
        <RouterLink :to="{ name: 'products', query: { category: 'writing' } }">
          书写纸品
        </RouterLink>
        <RouterLink :to="{ name: 'search', query: { q: '通勤' } }">查找“通勤”</RouterLink>
      </nav>
      <nav aria-labelledby="index-account-title">
        <p>02</p>
        <h2 id="index-account-title">账户事务</h2>
        <RouterLink to="/bag">购物袋 {{ bag.itemCount || "" }}</RouterLink>
        <RouterLink to="/orders">我的订单</RouterLink>
        <RouterLink to="/account/addresses">收货信息</RouterLink>
        <RouterLink to="/account/benefits">优惠权益</RouterLink>
        <RouterLink to="/account/notifications">通知</RouterLink>
      </nav>
      <nav aria-labelledby="index-service-title">
        <p>03</p>
        <h2 id="index-service-title">售后与支持</h2>
        <RouterLink to="/after-sales">售后服务</RouterLink>
        <RouterLink to="/support">联系素简记</RouterLink>
      </nav>
      <section class="theme-preference" aria-labelledby="index-theme-title">
        <p>04</p>
        <h2 id="index-theme-title">页面气质</h2>
        <ThemePreference />
      </section>
    </div>
    <RouterLink class="global-index__return" to="/">← 返回正在浏览的内容</RouterLink>
  </PjPageContainer>
</template>

<style scoped>
.global-index {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.global-index__intro {
  max-width: 54rem;
  padding-block: var(--pj-space-4) var(--pj-space-8);
}

.global-index__intro > p:first-child,
.global-index__grid > * > p:first-child {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
}

.global-index__intro h1 {
  max-width: 12ch;
  margin: 0;
  font-size: clamp(2.7rem, 6vw, 6rem);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: 0.98;
}

.global-index__intro > p:last-child {
  max-width: 38rem;
  margin: var(--pj-space-5) 0 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-md);
}

.global-index__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-7);
  margin-bottom: var(--pj-space-8);
}

.global-index__grid > * {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: stretch;
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-strong);
}

.global-index__grid h2 {
  margin: 0 0 var(--pj-space-4);
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
}

.global-index__grid nav a {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
  color: var(--pj-text-primary);
  text-decoration: none;
}

.global-index__grid nav a::after {
  content: "→";
  color: var(--pj-text-secondary);
}

.theme-preference :deep(.theme-preference) {
  padding-top: 0;
  border-top: 0;
}

.theme-preference :deep(.theme-preference > legend),
.theme-preference :deep(.theme-preference > p) {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

.global-index__return {
  color: var(--pj-text-primary);
  text-underline-offset: 0.25em;
}

@media (max-width: 64rem) {
  .global-index__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 40rem) {
  .global-index__grid {
    grid-template-columns: 1fr;
  }
}
</style>
