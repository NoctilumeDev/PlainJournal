<script setup lang="ts">
import { computed } from "vue";

import type { AfterSale } from "@plain-journal/foundation";

import {
  afterSaleProgress,
  afterSaleStatusPresentation,
} from "../../../entities/after-sale";

const props = defineProps<{
  afterSale: AfterSale;
}>();

const status = computed(() => afterSaleStatusPresentation(props.afterSale));
const stages = computed(() => afterSaleProgress(props.afterSale));
</script>

<template>
  <section class="after-sale-progress" aria-labelledby="after-sale-progress-title">
    <header class="after-sale-progress__header">
      <div>
        <p>当前旅程</p>
        <h2 id="after-sale-progress-title">当前进度</h2>
      </div>
      <span
        class="after-sale-progress__status"
        :class="`after-sale-progress__status--${status.tone}`"
      >
        {{ status.label }}
      </span>
    </header>

    <ol class="after-sale-progress__stages">
      <li
        v-for="(stage, index) in stages"
        :key="stage.key"
        :data-state="stage.state"
        :aria-current="stage.state === 'current' ? 'step' : undefined"
      >
        <span class="after-sale-progress__marker" aria-hidden="true">
          {{ stage.state === "completed" ? "✓" : index + 1 }}
        </span>
        <div>
          <strong>{{ stage.label }}</strong>
          <p>{{ stage.detail }}</p>
        </div>
      </li>
    </ol>

    <dl class="after-sale-progress__now">
      <div>
        <dt>当前处理方</dt>
        <dd>{{ status.owner }}</dd>
      </div>
      <div>
        <dt>下一步</dt>
        <dd>{{ status.nextAction }}</dd>
      </div>
      <div>
        <dt>时间说明</dt>
        <dd>{{ status.timing }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.after-sale-progress {
  display: grid;
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-5);
  border-block: 1px solid var(--pj-border-subtle);
}

.after-sale-progress__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.after-sale-progress__header p {
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.after-sale-progress__header h2 {
  margin: var(--pj-space-1) 0 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.after-sale-progress__status {
  flex: 0 0 auto;
  padding-bottom: var(--pj-space-1);
  border-bottom: 0.15rem solid var(--pj-border-strong);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.06em;
}

.after-sale-progress__status--processing {
  border-color: var(--pj-status-processing-line);
  color: var(--pj-status-processing-text);
}

.after-sale-progress__status--warning {
  border-color: var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
}

.after-sale-progress__status--attention {
  border-color: var(--pj-status-attention-line);
  color: var(--pj-status-attention-text);
}

.after-sale-progress__status--success {
  border-color: var(--pj-status-success-line);
  color: var(--pj-status-success-text);
}

.after-sale-progress__stages {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.after-sale-progress__stages li {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: start;
  gap: var(--pj-space-3);
  min-width: 0;
  padding-right: var(--pj-space-4);
  color: var(--pj-text-secondary);
}

.after-sale-progress__stages li:not(:last-child)::after {
  content: "";
  position: absolute;
  top: var(--pj-space-4);
  left: var(--pj-space-6);
  right: var(--pj-space-1);
  height: 1px;
  background: var(--pj-border-subtle);
}

.after-sale-progress__marker {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: var(--pj-space-6);
  height: var(--pj-space-6);
  border: 1px solid var(--pj-border-strong);
  border-radius: 50%;
  background: var(--pj-surface-default);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 700;
}

.after-sale-progress__stages strong {
  display: block;
  margin: var(--pj-space-1) 0 var(--pj-space-1);
  color: inherit;
  font-size: var(--pj-font-size-sm);
}

.after-sale-progress__stages p {
  margin: 0;
  font-size: var(--pj-font-size-xs);
  line-height: var(--pj-line-height-normal);
}

.after-sale-progress__stages li[data-state="completed"],
.after-sale-progress__stages li[data-state="current"] {
  color: var(--pj-text-primary);
}

.after-sale-progress__stages li[data-state="completed"] .after-sale-progress__marker,
.after-sale-progress__stages li[data-state="current"] .after-sale-progress__marker {
  border-color: var(--pj-action-primary);
  background: var(--pj-action-primary);
  color: var(--pj-action-on-primary);
}

.after-sale-progress__stages li[data-state="current"] .after-sale-progress__marker {
  outline: var(--pj-space-1) solid
    color-mix(in srgb, var(--pj-brand-primary-soft) 35%, transparent);
}

.after-sale-progress__stages li[data-state="stopped"] .after-sale-progress__marker {
  border-color: var(--pj-status-danger-line);
  color: var(--pj-status-danger-text);
}

.after-sale-progress__now {
  display: grid;
  grid-template-columns: 0.75fr 1.35fr 1.1fr;
  gap: var(--pj-space-4);
  margin: 0;
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.after-sale-progress__now div {
  min-width: 0;
}

.after-sale-progress__now dt {
  margin-bottom: var(--pj-space-1);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  letter-spacing: 0.08em;
}

.after-sale-progress__now dd {
  margin: 0;
  line-height: 1.65;
}

@media (max-width: 48rem) {
  .after-sale-progress__stages,
  .after-sale-progress__now {
    grid-template-columns: 1fr;
  }

  .after-sale-progress__stages {
    gap: var(--pj-space-4);
  }

  .after-sale-progress__stages li {
    padding: 0;
  }

  .after-sale-progress__stages li:not(:last-child)::after {
    top: var(--pj-space-6);
    bottom: calc(-1 * var(--pj-space-4));
    left: var(--pj-space-4);
    right: auto;
    width: 1px;
    height: auto;
  }
}

@media (max-width: 32rem) {
  .after-sale-progress__header {
    flex-direction: column;
  }
}
</style>
