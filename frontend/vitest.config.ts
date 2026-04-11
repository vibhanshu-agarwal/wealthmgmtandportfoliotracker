import { defineConfig } from "vitest/config";
import path from "node:path";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    server: {
      deps: {
        inline: ["fast-check"],
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
      // next-auth beta imports next/server — alias to the .js variant for Vitest compatibility
      "next/server": path.resolve(__dirname, "node_modules/next/server.js"),
      // fast-check v4 uses conditional exports that Vite cannot resolve on CI (Ubuntu)
      "fast-check": path.resolve(__dirname, "node_modules/fast-check/lib/fast-check.js"),
    },
  },
});
