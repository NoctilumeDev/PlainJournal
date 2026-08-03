<script setup lang="ts">
import type { PjResponsiveImageSource } from "./responsiveImage";

withDefaults(defineProps<{
  src: string;
  alt: string;
  sources?: PjResponsiveImageSource[];
  sizes?: string;
  width?: number;
  height?: number;
  loading?: "eager" | "lazy";
  decoding?: "sync" | "async" | "auto";
  fetchPriority?: "high" | "low" | "auto";
}>(), {
  sources: () => [],
  decoding: "async",
});

defineEmits<{
  error: [event: Event];
}>();
</script>

<template>
  <picture class="pj-responsive-image">
    <source
      v-for="source in sources"
      :key="source.type"
      :type="source.type"
      :srcset="source.srcset"
      :sizes="sizes"
    />
    <img
      class="pj-responsive-image__image"
      :src="src"
      :alt="alt"
      :sizes="sizes"
      :width="width"
      :height="height"
      :loading="loading"
      :decoding="decoding"
      :fetchpriority="fetchPriority"
      @error="$emit('error', $event)"
    />
  </picture>
</template>

<style scoped>
.pj-responsive-image,
.pj-responsive-image__image {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
