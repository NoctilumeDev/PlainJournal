<script setup lang="ts">
withDefaults(defineProps<{
  label?: string;
}>(), {
  label: "事务工作台",
});
</script>

<template>
  <section class="split-workbench" :aria-label="label">
    <aside class="split-workbench__rail">
      <slot name="rail" />
    </aside>
    <section class="split-workbench__queue">
      <slot name="queue" />
    </section>
    <section class="split-workbench__detail">
      <slot name="detail" />
    </section>
  </section>
</template>

<style scoped>
.split-workbench {
  --split-workbench-rail: clamp(13rem, 16vw, 15rem);
  --split-workbench-queue: clamp(23rem, 34vw, 32rem);

  min-width: 0;
  min-height: calc(100vh - var(--admin-shell-header-height));
  display: grid;
  grid-template-columns:
    var(--split-workbench-rail)
    var(--split-workbench-queue)
    minmax(30rem, 1fr);
  background: var(--pj-surface-default);
}

.split-workbench__rail,
.split-workbench__queue,
.split-workbench__detail {
  min-width: 0;
}

.split-workbench__rail,
.split-workbench__queue {
  border-right: 1px solid var(--pj-border-subtle);
}

@media (max-width: 72rem) {
  .split-workbench {
    grid-template-columns: minmax(12.5rem, 0.72fr) minmax(22rem, 1.28fr);
  }

  .split-workbench__detail {
    grid-column: 1 / -1;
    border-top: 1px solid var(--pj-border-subtle);
  }
}

@media (max-width: 48rem) {
  .split-workbench {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .split-workbench__rail,
  .split-workbench__queue,
  .split-workbench__detail {
    grid-column: auto;
    border-right: 0;
  }

  .split-workbench__queue,
  .split-workbench__detail {
    border-top: 1px solid var(--pj-border-subtle);
  }
}
</style>
