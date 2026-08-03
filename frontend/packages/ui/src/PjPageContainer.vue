<script setup lang="ts">
withDefaults(defineProps<{
  as?: string;
  size?: "content" | "wide" | "reading";
}>(), {
  as: "div",
  size: "content",
});
</script>

<template>
  <component
    :is="as"
    class="pj-page-container"
    :class="`pj-page-container--${size}`"
  >
    <slot />
  </component>
</template>
