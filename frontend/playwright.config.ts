import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const authFile = path.join(__dirname, "playwright/.auth/user.json");

// In CI, use the preinstalled Google Chrome binary so that
// `npx playwright install` (and its slow --with-deps apt pass) can be skipped.
// Locally, leave channel unset so developers use Playwright's own Chromium build.
const ciChannel = process.env.CI === "true" ? { channel: "chrome" as const } : {};

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  testIgnore: ["**/helpers/__tests__/**"],
  globalSetup: path.resolve(__dirname, "tests/e2e/global-setup.ts"),
  timeout: 120_000,
  retries: 0,
  // Ensure serial execution to respect AWS Lambda concurrency limits
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:3000",
    trace: { mode: "retain-on-failure", screenshots: false },
    headless: true,
    ...ciChannel,
  },
  projects: [
    // Setup project — runs the global login once and saves session state
    {
      name: "setup",
      testMatch: /.*\.setup\.ts/,
    },
    // Main test project — inherits authenticated session from setup
    {
      name: "chromium",
      testIgnore: [
        /dashboard-smoke\.spec\.ts$/,
        /aws-synthetic\/.*/,
        /azure-synthetic\/.*/,
        // Local-only Task 9.3 real-stack price proof — requires a flag-on build via
        // playwright.draft-prices.real.config.ts; must not run under this default
        // (picker-disabled) configuration.
        /asset-picker-prices\.integration\.spec\.ts$/,
        // One-off verification scripts from the azure-demo-readiness-phase1 spec (already
        // shipped) — plain ts-node scripts with no test()/describe() calls, meant to be run
        // directly (see each file's own header comment), not picked up as Playwright specs.
        // Their .test.ts naming matches Playwright's default testMatch glob, so without this
        // they get loaded — and their top-level side-effecting code executed — as a side
        // effect of every test run's file collection, adding ~15s of spawned child processes
        // and reporting a stale, silently-failing assertion nobody sees.
        /fix-verification\.test\.ts$/,
        /global-setup-entrypoint\.test\.ts$/,
        /global-setup-export\.test\.ts$/,
        /helpers[\\/]__tests__[\\/].*/,
        // B2 Task 9.1 — requires NEXT_PUBLIC_ENABLE_ASSET_PICKER=true, which this
        // config's own build never sets (Edit Holdings would have no entry point).
        // Local-only: run via its own playwright.asset-catalog.real.config.ts.
        /asset-catalog\.integration\.spec\.ts$/,
        // B2 Task 9.4 — real presence proof. Needs a flag-on build, the demo
        // account (not this project's Golden-State E2E user), and a gateway whose
        // presence TTL is shortened by docker-compose.presence-e2e.yml. Local-only:
        // run via its own playwright.presence.real.config.ts.
        /asset-picker-presence\.integration\.spec\.ts$/,
      ],
      use: {
        ...devices["Desktop Chrome"],
        storageState: authFile,
      },
      dependencies: ["setup"],
    },
    // No Spring stack: only checks the static export server (see frontend-ci e2e-smoke).
    {
      name: "static-smoke",
      testMatch: /dashboard-smoke\.spec\.ts$/,
      use: { ...devices["Desktop Chrome"] },
    },
    // Live AWS environment testing (synthetic monitoring)
    {
      name: "aws-synthetic",
      testDir: "./tests/e2e/aws-synthetic",
      use: {
        ...devices["Desktop Chrome"],
        baseURL: "https://vibhanshu-ai-portfolio.dev",
      },
      // Extended timeout to account for AWS Lambda / Bedrock cold starts (90s per handoff)
      timeout: 90_000,
    },
    // Live Azure environment testing (synthetic monitoring)
    // Targets the same public custom domain as the AWS suite; frontend and API
    // share vibhanshu-ai-portfolio.dev / api.vibhanshu-ai-portfolio.dev.
    {
      name: "azure-synthetic",
      testDir: "./tests/e2e/azure-synthetic",
      use: {
        ...devices["Desktop Chrome"],
        // Canonical public frontend domain — matches BASE_URL injected by workflows.
        baseURL: "https://vibhanshu-ai-portfolio.dev",
      },
      // 120s matches the suite-level budget; individual tests set their own
      // lower timeouts (70s API calls, 30s UI interactions).
      timeout: 120_000,
    },
  ],
  webServer: {
    command: "npm run build && npm run start:export",
    env: {
      ...process.env,
      // Single canonical name for the gateway base URL (browser and Node-side tests).
      NEXT_PUBLIC_API_BASE_URL:
        process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",
    },
    url: "http://localhost:3000",
    // In GitHub Actions, never attach to an arbitrary process already bound to :3000.
    reuseExistingServer: process.env.CI !== "true",
    timeout: 240_000,
  },
});
