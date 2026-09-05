import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// B2 Task 9.1 — dedicated REAL-stack config for the catalog integration spec.
//
// Deliberately separate from both playwright.config.ts (whose default build never
// sets NEXT_PUBLIC_ENABLE_ASSET_PICKER, so the picker has no reachable entry
// point) and playwright.asset-picker.mocked.config.ts (which enables the picker
// but fulfills every request via page.route — never touching a real gateway).
// This is the one config that builds with the picker enabled AND performs zero
// mocking: asset-catalog.integration.spec.ts's requests cross the real API
// Gateway at NEXT_PUBLIC_API_BASE_URL.
//
// LOCAL-ONLY — not invoked by any CI workflow (Task 9.9 owns that). Requires the
// Docker Compose stack up and healthy AND Golden State explicitly seeded first —
// this config carries no `globalSetup` of its own. See
// asset-catalog.integration.spec.ts's own header comment for the exact
// stack-up/env-var/seed/run sequence before invoking:
//
//   npx playwright test --config playwright.asset-catalog.real.config.ts
//
// reuseExistingServer is false for the same reason playwright.asset-picker.
// mocked.config.ts uses false: a stale server from a *different* config's build
// would silently serve the wrong flag state.
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  testMatch: /asset-catalog\.integration\.spec\.ts$/,
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
      // Distinct origin from the frontend's own (:3000) — the real gateway,
      // never a mock, so the browser's CORS boundary is genuinely exercised.
      NEXT_PUBLIC_API_BASE_URL:
        process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",
      NEXT_PUBLIC_ENABLE_ASSET_PICKER: "true",
    },
    url: "http://localhost:3000",
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
