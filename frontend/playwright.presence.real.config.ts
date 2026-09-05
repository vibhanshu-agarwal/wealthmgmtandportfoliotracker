import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// B2 Task 9.4 — dedicated REAL-stack config for demo presence integration.
//
// Separate from playwright.config.ts (picker flag off, and this spec is in its
// chromium `testIgnore`) and from the mocked picker configs, which fulfill
// routes. Nothing here fulfills `/api/presence/demo`: every presence answer the
// spec asserts on comes from the real gateway and real Redis.
//
// LOCAL-ONLY — not wired into CI (Task 9.9 owns broader Wave 9 CI wiring).
// Requires the Docker Compose stack up and healthy *with the presence overlay*,
// because the spec observes a session ageing out and the production TTL default
// is 150s. Full setup is in the spec's own header comment.
//
// `reuseExistingServer: false` so a stale flag-off build cannot satisfy the
// health check and silently produce a picker with no entry point.
//
// The timeout is deliberately generous: one leg of the proof waits out the
// gateway's configured presence TTL, and the demo portfolio is large enough
// that seeding the draft on open is not instant.
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  testMatch: /asset-picker-presence\.integration\.spec\.ts$/,
  timeout: 240_000,
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
      NEXT_PUBLIC_ENABLE_ASSET_PICKER: "true",
    },
    url: "http://localhost:3000",
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
