import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "lcov", "json-summary"],
      reportsDirectory: "../../coverage/foundation",
      include: ["src/**/*.ts"],
      exclude: ["src/**/*.test.ts"],
      reportOnFailure: true,
      thresholds: {
        lines: 70,
        branches: 60,
        functions: 70,
        statements: 70,
      },
    },
  },
});
