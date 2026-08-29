import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// Dedicated mocked E2E config for B2's Asset Picker flows (Checkpoint 4, Task 4 in
// the kickoff note). Deliberately separate from playwright.mocked.config.ts, not a
// widened testMatch on it: this is the ONLY place NEXT_PUBLIC_ENABLE_ASSET_PICKER is
// ever "true" for a build this repo produces, and that must never leak into the
// shared mocked config's own build (which portfolio-deep-link.spec.ts depends on
// staying default-disabled) or into any workflow/deployment environment.
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
  testMatch: /asset-picker\.spec\.ts$/,
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
