<script setup lang="ts">
import { PjButton } from "@plain-journal/ui";

defineProps<{
  loading?: boolean;
  loadingMessage?: string;
  error?: string | null;
  errorEyebrow?: string;
  errorTitle?: string;
  retryLabel?: string;
  empty?: boolean;
  emptyEyebrow?: string;
  emptyTitle?: string;
  emptyMessage?: string;
}>();

defineEmits<{
  retry: [];
}>();
</script>

<template>
  <div v-if="loading" class="async-state" role="status" aria-live="polite">
    <span class="loading-line" />
    <span class="loading-line loading-line--short" />
    <p>{{ loadingMessage ?? "正在读取信息…" }}</p>
  </div>
  <div v-else-if="error" class="async-state async-state--error" role="alert">
    <p class="eyebrow">{{ errorEyebrow ?? "暂时无法继续" }}</p>
    <h2>{{ errorTitle ?? "信息没有被加载为成功。" }}</h2>
    <p>{{ error }}</p>
    <PjButton variant="text" @click="$emit('retry')">
      {{ retryLabel ?? "重新读取 →" }}
    </PjButton>
  </div>
  <div v-else-if="empty" class="async-state">
    <p class="eyebrow">{{ emptyEyebrow ?? "没有匹配结果" }}</p>
    <h2>{{ emptyTitle ?? "这里暂时没有商品。" }}</h2>
    <p>{{ emptyMessage ?? "可以调整查找词或返回全部商品。" }}</p>
  </div>
  <slot v-else />
</template>
