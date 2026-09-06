import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// Dedicated mocked E2E config for B2's preserved Wave-1 Asset Picker flows. It
// deliberately collects only `asset-picker.mocked.spec.ts`; Task 9.7's required
// `asset-picker.spec.ts` is a real gateway/backend proof and must never run here.
// This remains separate from playwright.mocked.config.ts, so enabling the picker
// cannot leak into that shared mocked build or into any workflow/deployment env.
//
//   npx playwright test --config playwright.asset-picker.mocked.config.ts
//
// reuseExistingServer is intentionally false (unlike the shared mocked config): a
// stale server left running from a *different* config's build would silently serve
// the wrong flag state — Playwright's health check only confirms something answers
// on the port, not which flags its build was compiled with.
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  testMatch: /asset-picker\.mocked\.spec\.ts$/,
  timeout: 60_000,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    headless: true,
    ...ciChannel,
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run build && npm run start:export",
    env: {
      ...process.env,
      NEXT_PUBLIC_API_BASE_URL:
        process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",
      // The one and only place this repo ever builds with the picker enabled.
      NEXT_PUBLIC_ENABLE_ASSET_PICKER: "true",
    },
    url: "http://localhost:3000",
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
