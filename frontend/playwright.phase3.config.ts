/**
 * Phase 3 multi-user browser suite. Standalone: inherits nothing from tests/e2e and is
 * not wired into any workflow. See
 * docs/superpowers/specs/2026-09-22-phase3-multi-user-e2e-suite-design.md.
 *
 * Local:      P3_TARGET=local P3_WORK_DIR=<abs dir outside repo> npx playwright test -c playwright.phase3.config.ts
 * Production: owner-operated only, after separate approval (see the design, section 10).
 */
import { randomBytes } from "node:crypto";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { defineConfig } from "@playwright/test";
import { resolveRunConfig } from "./tests/phase3-e2e/lib/config";

const resolved = resolveRunConfig(process.env, {
  repoRoot: path.resolve(__dirname, ".."),
  now: new Date(),
  randomHex: () => randomBytes(2).toString("hex"),
});
if (!resolved.ok) {
  throw new Error(`[phase3] refusing to run: ${resolved.problems.join(", ")}`);
}
const run = resolved.config;
const runDir = path.join(run.workDir, run.runId);
mkdirSync(runDir, { recursive: true });

// Pin run identity and generated credentials in the main process so every worker
// resolves the same run (workers re-evaluate this file with the inherited env).
process.env.P3_RUN_ID = run.runId;
process.env.P3_RUN_DIR = runDir;
process.env.P3_FRESH_PASSWORD = run.fresh.password;
if (run.mode === "local") {
  process.env.P3_CERT_A_EMAIL = run.certA.email;
  process.env.P3_CERT_A_PASSWORD = run.certA.password;
  process.env.P3_CERT_B_EMAIL = run.certB.email;
  process.env.P3_CERT_B_PASSWORD = run.certB.password;
}

export default defineConfig({
  testDir: "./tests/phase3-e2e",
  testMatch: "phase3.spec.ts",
  outputDir: path.join(runDir, "pw-output"),
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // Production stops at the first failure. The scenarios are NOT serial-mode, so without
  // this a failed S00 — including a served build id that is not the one the deploy run
  // uploaded — would be followed by S02, which creates a PERMANENT Production account.
  // Local runs keep going: they are meant to report every scenario in one pass.
  maxFailures: run.mode === "production" ? 1 : undefined,
  forbidOnly: true,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  reporter: [["list"], ["./tests/phase3-e2e/lib/verdict-reporter.ts"]],
  globalTeardown: "./tests/phase3-e2e/global-teardown.ts",
  use: {
    baseURL: run.frontend,
    viewport: { width: 1440, height: 900 },
    serviceWorkers: "block",
    launchOptions: { args: ["--no-proxy-server"] },
    // Traces (always) and videos (on failure) are managed per context in phase3.spec.ts:
    // the runner's implicit tracing of browser.newContext() contexts hung at teardown.
    trace: "off",
    video: "off",
    screenshot: "off",
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
  },
  webServer:
    run.mode === "local"
      ? {
          command: "node node_modules/serve/build/main.js out -l tcp://localhost:3000 --no-port-switching",
          url: "http://localhost:3000/login",
          reuseExistingServer: false,
          timeout: 60_000,
        }
      : undefined,
});
