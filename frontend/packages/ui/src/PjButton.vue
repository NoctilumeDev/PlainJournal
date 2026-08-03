<script setup lang="ts">
withDefaults(defineProps<{
  variant?: "primary" | "secondary" | "text" | "destructive";
  type?: "button" | "submit" | "reset";
  disabled?: boolean;
  loading?: boolean;
  block?: boolean;
}>(), {
  variant: "primary",
  type: "button",
  disabled: false,
  loading: false,
  block: false,
});
</script>

<template>
  <button
    class="pj-button"
    :class="[
      `pj-button--${variant}`,
      {
        'pj-button--block': block,
        'pj-button--loading': loading,
      },
    ]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading ? 'true' : undefined"
  >
    <span v-if="loading" class="pj-button__spinner" aria-hidden="true" />
    <span class="pj-button__label"><slot /></span>
  </button>
</template>
