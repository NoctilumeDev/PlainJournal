<script setup lang="ts">
withDefaults(defineProps<{
  label?: string;
}>(), {
  label: "清单工作区",
});
</script>

<template>
  <section class="list-workbench" :aria-label="label">
    <aside class="list-workbench__filters">
      <slot name="filters" />
    </aside>
    <section class="list-workbench__list">
      <slot name="list" />
    </section>
    <section class="list-workbench__detail">
      <slot name="detail" />
    </section>
  </section>
</template>

<style scoped>
.list-workbench {
  --list-workbench-filters: clamp(15rem, 20vw, 18rem);
  --list-workbench-list: clamp(25rem, 33vw, 32rem);

  min-width: 0;
  min-height: calc(100vh - var(--admin-shell-header-height));
  display: grid;
  grid-template-columns:
    var(--list-workbench-filters)
    var(--list-workbench-list)
    minmax(30rem, 1fr);
  background: var(--pj-surface-default);
}

.list-workbench__filters,
.list-workbench__list,
.list-workbench__detail {
  min-width: 0;
}

.list-workbench__filters,
.list-workbench__list {
  border-right: 1px solid var(--pj-border-subtle);
}

@media (max-width: 72rem) {
  .list-workbench {
    grid-template-columns: minmax(14rem, 0.75fr) minmax(24rem, 1.25fr);
  }

  .list-workbench__detail {
    grid-column: 1 / -1;
    border-top: 1px solid var(--pj-border-subtle);
  }
}

@media (max-width: 48rem) {
  .list-workbench {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .list-workbench__filters,
  .list-workbench__list,
  .list-workbench__detail {
    grid-column: auto;
    border-right: 0;
  }

  .list-workbench__list,
  .list-workbench__detail {
    border-top: 1px solid var(--pj-border-subtle);
  }
}
</style>
