import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

// Stack-less config for fully mocked E2E specs (page.route intercepts all
// gateway calls). Lets developers run the portfolio deep-link regression test
// without the Docker Compose backend:
//
//   npx playwright test --config playwright.mocked.config.ts
//
// In CI the same spec also runs against the real stack via the main config's
// chromium project (mocks take precedence over the network either way).
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  // Also matches asset-picker-disabled-by-default.spec.ts (B2 Checkpoint 4): that
  // spec's whole point is verifying the DEFAULT build (no flags set — same build this
  // config already produces) has no reachable picker entry point, so it belongs on
  // this config's own webServer, not the picker-enabled one in
  // playwright.asset-picker.mocked.config.ts.
  testMatch: /(portfolio-deep-link|asset-picker-disabled-by-default)\.spec\.ts$/,
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
    },
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 300_000,
  },
});
