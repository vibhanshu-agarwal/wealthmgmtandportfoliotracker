/**
 * Two scenarios mirroring the ordering that matters in the Phase 3 suite: the S00 build-id
 * check first, then the S02 signup. S02 here creates nothing — it only records that it
 * STARTED, because "did this scenario run at all?" is the question the control answers.
 *
 * Driven by run-control.mjs, which runs this file three times and decides the verdict. It
 * is not part of the Phase 3 suite: playwright.phase3.config.ts matches only
 * phase3.spec.ts, and vitest's include list does not cover this directory.
 */
import fs from "node:fs";
import { expect, test } from "@playwright/test";

const ORIGIN = process.env.CONTROL_ORIGIN as string;
const EXPECTED_BUILD_ID = process.env.CONTROL_EXPECTED_BUILD_ID as string;
const MARKER = process.env.CONTROL_MARKER as string;

test("S00 preflight: served build matches the emitted candidate", async () => {
  const html = await (await fetch(`${ORIGIN}/login`)).text();
  const buildId = /\\"b\\":\\"([A-Za-z0-9_-]{10,})\\"/.exec(html)?.[1] ?? null;
  // Same failure shape as evidence.verify, which calls expect(...).toBe(true) and therefore
  // throws, aborting the scenario body before it can touch anything.
  expect(buildId, "served build ID equals the expected candidate").toBe(EXPECTED_BUILD_ID);
});

test("S02 successful signup lands on an empty portfolio", async () => {
  // Stands in for the signup that creates a PERMANENT Production account. Reaching this
  // line at all, after S00 has failed, is the exposure the stop gate exists to close.
  fs.writeFileSync(MARKER, "S02 started\n", "utf-8");
  // Fault injection for the runner's own negative control: a scenario that starts, leaves
  // its side effect behind, and THEN fails. An oracle that only looked for the marker would
  // call this a pass.
  if (process.env.CONTROL_FAIL_S02_AFTER_MARKER === "1") {
    throw new Error("injected S02 failure after the side effect");
  }
});
