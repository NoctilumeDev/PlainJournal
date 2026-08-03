<script setup lang="ts">
import {
  useThemeStore,
  type StorefrontTheme,
} from "../model/theme";

const theme = useThemeStore();

function selectTheme(value: StorefrontTheme) {
  theme.setTheme(value);
}
</script>

<template>
  <fieldset class="theme-selector">
    <legend>页面气质</legend>
    <p>同一套设计语言，两种克制的浏览气候。</p>
    <label>
      <input
        type="radio"
        name="storefront-theme"
        value="subai"
        :checked="theme.theme === 'subai'"
        @change="selectTheme('subai')"
      />
      <span>
        <strong>素白</strong>
        <small>清楚、稳定，让商品保持原貌。</small>
      </span>
    </label>
    <label>
      <input
        type="radio"
        name="storefront-theme"
        value="qinghe"
        :checked="theme.theme === 'qinghe'"
        @change="selectTheme('qinghe')"
      />
      <span>
        <strong>青荷</strong>
        <small>清润、自然，为浏览增加一点呼吸。</small>
      </span>
    </label>
  </fieldset>
</template>

<style scoped>
.theme-selector {
  width: 100%;
  margin: 0;
  padding: 0;
  border: 0;
}

.theme-selector legend {
  margin: 0 0 var(--pj-space-3);
  font-size: var(--pj-font-size-md);
  font-weight: 650;
}

.theme-selector > p {
  margin: 0 0 var(--pj-space-4);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.theme-selector label {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--pj-space-3);
  align-items: start;
  padding-block: var(--pj-space-3);
  border-top: 1px solid var(--pj-color-line);
  cursor: pointer;
}

.theme-selector label:last-child {
  border-bottom: 1px solid var(--pj-color-line);
}

.theme-selector label:has(input:checked) {
  background:
    color-mix(
      in srgb,
      var(--pj-color-accent-soft) 18%,
      transparent
    );
}

.theme-selector label:has(input:checked) small {
  color: var(--pj-text-primary);
}

.theme-selector input {
  width: 1rem;
  height: 1rem;
  margin: 0.25rem 0 0;
  accent-color: var(--pj-color-accent);
}

.theme-selector label > span {
  display: grid;
  gap: var(--pj-space-1);
}

.theme-selector strong {
  color: var(--pj-color-text);
  font-weight: 600;
}

.theme-selector small {
  color: var(--pj-color-muted);
  line-height: 1.5;
}
</style>
