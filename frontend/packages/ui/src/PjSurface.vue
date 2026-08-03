<script setup lang="ts">
withDefaults(defineProps<{
  as?: string;
  tone?: "plain" | "soft" | "raised" | "media";
  padding?: "none" | "small" | "medium" | "large";
}>(), {
  as: "section",
  tone: "plain",
  padding: "medium",
});
</script>

<template>
  <component
    :is="as"
    class="pj-surface"
    :class="[
      `pj-surface--${tone}`,
      `pj-surface--padding-${padding}`,
    ]"
  >
    <slot />
  </component>
</template>
