<script setup lang="ts">
import { RouterLink } from "vue-router";
</script>

<template>
  <section class="not-found">
    <p class="eyebrow">404</p>
    <h1>这里没有可继续的内容。</h1>
    <p>链接可能已经变化，商品事实不会由这个页面猜测。</p>
    <RouterLink class="primary-action" to="/">返回素简记 →</RouterLink>
  </section>
</template>
