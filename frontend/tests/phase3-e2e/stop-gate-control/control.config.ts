/**
 * Runner settings identical to what playwright.phase3.config.ts uses for a Production run,
 * except that maxFailures comes from the environment so the control can run the same
 * scenarios with the gate on and with it off.
 *
 * No browser is launched: both scenarios use fetch and fs, so the control needs no browser
 * download and runs in any job that has the frontend dependencies.
 */
import { defineConfig } from "@playwright/test";

const maxFailures = Number(process.env.CONTROL_MAX_FAILURES ?? "0");
const jsonOut = process.env.CONTROL_JSON_OUT;

export default defineConfig({
  testDir: __dirname,
  testMatch: "control.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // 0 means "no limit" to Playwright, which is the ungated arm.
  maxFailures,
  // JSON alongside list: the runner decides the verdict from structured per-scenario
  // outcomes and the process exit status, never from reporter prose.
  reporter: jsonOut ? [["list"], ["json", { outputFile: jsonOut }]] : [["list"]],
  use: { trace: "off", video: "off", screenshot: "off" },
});
