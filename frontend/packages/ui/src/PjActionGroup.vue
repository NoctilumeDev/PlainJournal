<script setup lang="ts">
withDefaults(defineProps<{
  align?: "start" | "end" | "between";
  stackOnCompact?: boolean;
}>(), {
  align: "start",
  stackOnCompact: true,
});
</script>

<template>
  <div
    class="pj-action-group"
    :class="[
      `pj-action-group--${align}`,
      { 'pj-action-group--stack-compact': stackOnCompact },
    ]"
  >
    <slot />
  </div>
</template>
