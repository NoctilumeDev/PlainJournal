import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  DEFAULT_THEME,
  THEME_STORAGE_KEY,
  useThemeStore,
} from "./theme";

describe("storefront theme", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute("data-pj-theme");
    document.head.innerHTML = '<meta name="theme-color" content="#000000" />';
    setActivePinia(createPinia());
  });

  it("uses Qinghe by default without inventing a stored preference", () => {
    const theme = useThemeStore();

    theme.initialize();

    expect(theme.theme).toBe(DEFAULT_THEME);
    expect(document.documentElement.dataset.pjTheme).toBe("qinghe");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  it("persists Subai and updates the document theme color", () => {
    const theme = useThemeStore();

    theme.setTheme("subai");

    expect(theme.theme).toBe("subai");
    expect(theme.label).toBe("素白");
    expect(document.documentElement.dataset.pjTheme).toBe("subai");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("subai");
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute("content"))
      .toBe("#f7f7f5");
  });

  it("restores only supported persisted values", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "lotus-animation");
    const invalidTheme = useThemeStore();
    invalidTheme.initialize();
    expect(invalidTheme.theme).toBe("qinghe");

    setActivePinia(createPinia());
    localStorage.setItem(THEME_STORAGE_KEY, "qinghe");
    const restoredTheme = useThemeStore();
    restoredTheme.initialize();

    expect(restoredTheme.theme).toBe("qinghe");
    expect(document.documentElement.dataset.pjTheme).toBe("qinghe");
  });
});
