import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const authFile = path.join(__dirname, "playwright/.auth/user.json");

export default defineConfig({
  testDir: "./tests/e2e",
  globalSetup: "./tests/e2e/global-setup.ts",
  timeout: 120_000,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
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
      use: {
        ...devices["Desktop Chrome"],
        storageState: authFile,
      },
      dependencies: ["setup"],
    },
  ],
  webServer: {
    command: "npm run build && npm run start:export",
    env: {
      ...process.env,
      // Prefer CI/job env (localhost) so browser Origin matches gateway CORS.
      NEXT_PUBLIC_API_BASE_URL:
        process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",
    },
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 240_000,
  },
});
