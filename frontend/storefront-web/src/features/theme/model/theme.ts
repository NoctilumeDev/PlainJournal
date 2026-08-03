import { computed, ref } from "vue";
import { defineStore } from "pinia";

export const THEME_STORAGE_KEY = "sujianji-theme";
export const DEFAULT_THEME = "qinghe";

export type StorefrontTheme = "subai" | "qinghe";

const THEME_COLORS: Record<StorefrontTheme, string> = {
  subai: "#f7f7f5",
  qinghe: "#f2f7f6",
};

function isStorefrontTheme(value: string | null): value is StorefrontTheme {
  return value === "subai" || value === "qinghe";
}

function readStoredTheme(): StorefrontTheme {
  if (typeof localStorage === "undefined") {
    return DEFAULT_THEME;
  }
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return isStorefrontTheme(stored) ? stored : DEFAULT_THEME;
  } catch {
    return DEFAULT_THEME;
  }
}

function applyTheme(theme: StorefrontTheme) {
  if (typeof document === "undefined") {
    return;
  }
  document.documentElement.dataset.pjTheme = theme;
  document
    .querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    ?.setAttribute("content", THEME_COLORS[theme]);
}

function persistTheme(theme: StorefrontTheme) {
  if (typeof localStorage === "undefined") {
    return;
  }
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // The selected theme still applies for the current page when storage is unavailable.
  }
}

export const useThemeStore = defineStore("storefront-theme", () => {
  const theme = ref<StorefrontTheme>(DEFAULT_THEME);
  const initialized = ref(false);
  const label = computed(() => theme.value === "qinghe" ? "青荷" : "素白");

  function initialize() {
    theme.value = readStoredTheme();
    applyTheme(theme.value);
    initialized.value = true;
  }

  function setTheme(nextTheme: StorefrontTheme) {
    theme.value = nextTheme;
    applyTheme(nextTheme);
    persistTheme(nextTheme);
    initialized.value = true;
  }

  return {
    theme,
    initialized,
    label,
    initialize,
    setTheme,
  };
});
