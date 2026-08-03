<script setup lang="ts">
import { AsyncState } from "../../../shared/ui";

withDefaults(defineProps<{
  loading?: boolean;
  error?: string | null;
  empty?: boolean;
  emptyTitle?: string;
  emptyMessage?: string;
}>(), {
  loading: false,
  error: null,
  empty: false,
  emptyTitle: "这里暂时没有商品。",
  emptyMessage: "可以调整查找词或返回全部商品。",
});

defineEmits<{
  retry: [];
}>();
</script>

<template>
  <AsyncState
    :loading="loading"
    loading-message="正在整理商品信息…"
    :error="error"
    error-title="商品信息没有被加载为成功。"
    retry-label="重新读取商品 →"
    :empty="empty"
    :empty-title="emptyTitle"
    :empty-message="emptyMessage"
    @retry="$emit('retry')"
  >
    <slot />
  </AsyncState>
</template>
