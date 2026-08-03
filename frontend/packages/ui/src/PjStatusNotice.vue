<script setup lang="ts">
import { computed, useSlots } from "vue";

const props = withDefaults(defineProps<{
  tone?: "neutral" | "success" | "warning" | "danger" | "processing" | "unknown" | "attention" | "refunded";
  title?: string;
  assertive?: boolean;
}>(), {
  tone: "neutral",
  assertive: false,
});

const slots = useSlots();
const role = computed(() => props.assertive || ["danger", "attention"].includes(props.tone)
  ? "alert"
  : "status");
const live = computed(() => role.value === "alert" ? "assertive" : "polite");
</script>

<template>
  <section
    class="pj-status-notice"
    :class="`pj-status-notice--${tone}`"
    :role="role"
    :aria-live="live"
  >
    <div class="pj-status-notice__body">
      <strong v-if="title">{{ title }}</strong>
      <div class="pj-status-notice__content"><slot /></div>
    </div>
    <div v-if="slots.actions" class="pj-status-notice__actions">
      <slot name="actions" />
    </div>
  </section>
</template>
