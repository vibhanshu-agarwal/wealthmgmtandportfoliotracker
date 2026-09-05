import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// B2 Task 9.5 — dedicated REAL-stack config for portfolio asset-price freshness.
//
// Separate from playwright.config.ts (this spec is in its chromium testIgnore)
// and from picker-focused real/mocked configs. FreshnessStatus renders on the
// Portfolio page without NEXT_PUBLIC_ENABLE_ASSET_PICKER.
//
// LOCAL-ONLY — not wired into CI (Task 9.9 owns broader Wave 9 CI). Requires the
// Docker Compose stack up and healthy with Golden State + market-data seeded:
//
//   npx playwright test --config playwright.asset-freshness.real.config.ts
//
// reuseExistingServer is false so a stale process cannot satisfy the health check.
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  testMatch: /asset-price-freshness\.integration\.spec\.ts$/,
  timeout: 180_000,
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
      // FreshnessStatus does not require the Asset Picker flag.
    },
    url: "http://localhost:3000",
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
