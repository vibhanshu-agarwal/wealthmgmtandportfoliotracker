import { defineConfig } from "@playwright/test";

// Config verified for golden-path.spec.ts:
// - testDir: "./tests/e2e" ensures golden-path.spec.ts (and any other specs under tests/e2e/) are picked up automatically.
// - webServer.reuseExistingServer: true allows the suite to run against an already-running Next.js instance
//   (e.g. `npm run dev` or a live local stack) without spinning up a new server.
export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 120_000,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
    headless: true,
  },
  webServer: {
    command: "npm run build && npm run start:standalone",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 240_000,
  },
});
