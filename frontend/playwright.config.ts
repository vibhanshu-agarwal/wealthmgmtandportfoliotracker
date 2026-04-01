import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 120_000,
  retries: 0,
  use: {
    baseURL: "http://127.0.0.1:3000",
    trace: "on-first-retry",
    headless: true,
  },
  webServer: {
    command: "npm run build && npm run start:standalone",
    url: "http://127.0.0.1:3000",
    reuseExistingServer: true,
    timeout: 240_000,
  },
});
