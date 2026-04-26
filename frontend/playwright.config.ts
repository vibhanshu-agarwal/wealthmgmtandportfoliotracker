import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const authFile = path.join(__dirname, "playwright/.auth/user.json");

export default defineConfig({
  testDir: path.resolve(__dirname, "tests/e2e"),
  globalSetup: path.resolve(__dirname, "tests/e2e/global-setup.ts"),
  timeout: 120_000,
  retries: 0,
  // Ensure serial execution to respect AWS Lambda concurrency limits
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    headless: true,
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
      testIgnore: [/dashboard-smoke\.spec\.ts$/, /aws-synthetic\/.*/],
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
