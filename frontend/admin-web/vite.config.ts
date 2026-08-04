import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const proxy = {
    "/api": {
      target: env.VITE_API_PROXY_TARGET?.trim() || "http://127.0.0.1:18000",
      changeOrigin: true,
    },
    "/ws": {
      target: env.VITE_API_PROXY_TARGET?.trim() || "http://127.0.0.1:18000",
      changeOrigin: true,
      ws: true,
    },
  };
  return {
    plugins: [vue()],
    publicDir: "../storefront-web/public",
    server: {
      proxy,
    },
    preview: {
      proxy,
    },
    test: {
      environment: "jsdom",
      coverage: {
        provider: "v8",
        reporter: ["text", "html", "lcov", "json-summary"],
        reportsDirectory: "../coverage/admin",
        include: ["src/**/*.{ts,vue}"],
        exclude: ["src/**/*.d.ts", "src/**/*.test.ts", "src/main.ts"],
        reportOnFailure: true,
        thresholds: {
          lines: 60,
          branches: 45,
          functions: 45,
          statements: 60,
        },
      },
    },
  };
});
