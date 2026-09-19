import { defineConfig, devices } from "@playwright/test";
import os from "node:os";
import path from "node:path";
import { ENV, resolveActionTimeoutMsForConfig, resolveTestTimeoutMs } from "./lib/environment";

// Wave 10.2 Step B, exit criterion 5b - dedicated PRODUCTION browser config.
//
// AUTHORING ONLY: never run by an agent. Owner-operated through
// scripts/verify_step_b_5b.py, which invokes (never through npm or npx):
//   node node_modules/@playwright/test/cli.js test -c tests/production-e2e/playwright.step-b-5b.config.ts
// from frontend/, with an allowlisted child environment (spec Appendix B).
//
// Deliberately NOT the default config: that one runs a seeding globalSetup, a
// storageState setup project and a localhost webServer. This config has none of
// them, keeps trace/video/screenshot off (the demo token and password must never
// land in an artifact), reports nothing (the ledger is the only evidence channel),
// and collects exactly one spec. It lives outside tests/e2e so no existing config
// can collect it.
//
// The only environment reads here are the two the spec names for the config
// (output directory and timeout); both fall back safely, so `playwright test
// --list` works with no STEP_B_5B_* variable set. Everything else, including the
// target allowlist check, happens inside the test body.
const actionTimeoutMs = resolveActionTimeoutMsForConfig(process.env[ENV.ACTION_TIMEOUT_MS]);
const workDirValue = process.env[ENV.WORK_DIR];
const workDir =
  workDirValue !== undefined && workDirValue.trim() !== ""
    ? path.resolve(workDirValue)
    : path.join(os.tmpdir(), "wave10-5b-work");

// Ten legs, each bounded by action timeouts. The test-level timeout must outlast a slow honest run (one that
// fires first can cut the test body off before its finally block records every leg), yet fire before the
// orchestrator's own deadline (2,700 s from the login, a hard limit that kills the child regardless). The
// arithmetic lives in lib/environment.ts and is pinned by a test; at the orchestrator's default action
// timeout of 120 s it is 1,800 s.
const testTimeoutMs = resolveTestTimeoutMs(actionTimeoutMs);

export default defineConfig({
  testDir: __dirname,
  testMatch: /step-b-5b\.spec\.ts$/,
  // The ledger lives in <work>/ledger.jsonl, outside this directory, so Playwright's
  // own output cleanup can never touch it.
  outputDir: path.join(workDir, "pw-output"),
  preserveOutput: "never",
  timeout: testTimeoutMs,
  expect: { timeout: actionTimeoutMs },
  forbidOnly: true,
  retries: 0,
  workers: 1,
  fullyParallel: false,
  reporter: "null",
  use: {
    trace: "off",
    video: "off",
    screenshot: "off",
    headless: true,
    actionTimeout: actionTimeoutMs,
    navigationTimeout: actionTimeoutMs,
    acceptDownloads: false,
    serviceWorkers: "block",
    // Transport: every request the page makes carries the demo JWT, so it must go DIRECT to the two
    // allowlisted origins. Chromium otherwise follows the operating-system and auto-detected (WPAD)
    // proxy settings, which would carry that traffic through a host the allowlist never named.
    // This switch disables proxy use entirely; no Playwright proxy option is set here or anywhere
    // else in this directory (a proxy option, even a direct one, is still a proxy setting).
    launchOptions: { args: ["--no-proxy-server"] },
  },
  projects: [
    {
      name: "step-b-5b-chromium",
      use: {
        ...devices["Desktop Chrome"],
        // The orchestrator's P0 probe checks the executable that playwright-core's chromium.executablePath()
        // names: the FULL Chromium build. With `headless: true` and no channel, Playwright launches the separate
        // chromium-headless-shell build instead (chromium.js getExecutableName), so a host holding only one of the
        // two would pass or fail the probe for a binary the child never starts. `channel: "chromium"` selects the
        // full build (Playwright's documented switch for new headless mode) and keeps the probe and the launch
        // on the same file. It only picks the executable: launchOptions.args above still reach the browser.
        channel: "chromium",
      },
    },
  ],
});
