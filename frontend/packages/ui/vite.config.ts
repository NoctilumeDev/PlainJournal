import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "jsdom",
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "lcov", "json-summary"],
      reportsDirectory: "../../coverage/ui",
      include: ["src/**/*.{ts,vue}"],
      exclude: ["src/**/*.test.ts"],
      reportOnFailure: true,
      thresholds: {
        lines: 90,
        branches: 65,
        functions: 90,
        statements: 90,
      },
    },
  },
});
