<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  label: string;
  forId: string;
  hint?: string;
  error?: string | null;
  required?: boolean;
}>();

const hintId = computed(() => props.hint ? `${props.forId}-hint` : null);
const errorId = computed(() => props.error ? `${props.forId}-error` : null);
const describedBy = computed(() => [
  hintId.value,
  errorId.value,
].filter(Boolean).join(" ") || undefined);
</script>

<template>
  <div class="pj-field" :class="{ 'pj-field--error': Boolean(error) }">
    <label class="pj-field__label" :for="forId">
      {{ label }}
      <span v-if="required" class="pj-field__required" aria-hidden="true">*</span>
    </label>
    <slot :described-by="describedBy" :invalid="Boolean(error)" />
    <p v-if="hint" :id="hintId ?? undefined" class="pj-field__hint">{{ hint }}</p>
    <p v-if="error" :id="errorId ?? undefined" class="pj-field__error" role="alert">
      {{ error }}
    </p>
  </div>
</template>
