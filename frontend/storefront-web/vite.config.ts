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
    server: {
      proxy,
    },
    preview: {
      proxy,
    },
    test: {
      environment: "jsdom",
    },
  };
});
