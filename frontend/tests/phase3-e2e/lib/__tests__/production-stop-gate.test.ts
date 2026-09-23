/**
 * The Production stop gate (B3).
 *
 * A Production run must not be able to reach S02 — which creates a PERMANENT account —
 * after S00 has found that the origin is serving a build other than the one the deploy run
 * uploaded. Three things have to hold together, and each is pinned here because removing
 * any one of them silently re-opens the exposure:
 *
 *   1. resolveRunConfig refuses a Production run with no well-formed expected build id.
 *      That check runs when playwright.phase3.config.ts loads, before a single test is
 *      collected (covered by config.test.ts).
 *   2. S00 compares against the validated config value, not the raw environment variable,
 *      so the comparison cannot be skipped by leaving the variable unset.
 *   3. maxFailures stops a Production run at the first failure. The scenarios are NOT
 *      serial-mode, so a failed S00 would otherwise be followed by S02.
 *
 * Demonstrated against a local server, never Production: with maxFailures unset the signup
 * scenario runs after S00 fails; with maxFailures = 1 it does not run at all.
 */
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const FRONTEND_ROOT = path.resolve(__dirname, "../../../..");
const read = (relative: string) => fs.readFileSync(path.join(FRONTEND_ROOT, relative), "utf-8");

describe("Production stop gate", () => {
  const config = read("playwright.phase3.config.ts");
  const spec = read("tests/phase3-e2e/phase3.spec.ts");

  it("stops a Production run at the first failure", () => {
    expect(config).toMatch(/maxFailures:\s*run\.mode === "production" \? 1 : undefined/);
  });

  it("leaves local runs free to report every scenario in one pass", () => {
    // A bare `maxFailures: 1` would also truncate local runs, which exist to surface all
    // scenarios at once.
    expect(config).not.toMatch(/maxFailures:\s*1\s*,/);
  });

  it("keeps one worker and declaration order, which the gate depends on", () => {
    expect(config).toMatch(/workers:\s*1/);
    expect(config).toMatch(/fullyParallel:\s*false/);
    expect(config).toMatch(/retries:\s*0/);
  });

  it("runs the stop-gate control in CI, so the gate's effect is tested not assumed", () => {
    const workflow = fs.readFileSync(
      path.join(FRONTEND_ROOT, "..", ".github", "workflows", "frontend-ci.yml"),
      "utf-8",
    );
    expect(workflow).toContain("node tests/phase3-e2e/stop-gate-control/run-control.mjs");
  });

  it("keeps the control hermetic: no origin but loopback", () => {
    const dir = path.join(FRONTEND_ROOT, "tests", "phase3-e2e", "stop-gate-control");
    const sources = fs.readdirSync(dir).map((f) => fs.readFileSync(path.join(dir, f), "utf-8"));
    for (const source of sources) {
      expect(source).not.toContain("vibhanshu-ai-portfolio.dev");
      for (const url of source.match(/https?:\/\/[^\s"'`)]+/g) ?? []) {
        expect(url).toMatch(/^https?:\/\/127\.0\.0\.1/);
      }
    }
  });

  it("compares the served build against the validated config value", () => {
    expect(spec).toMatch(/buildId === run\.expectedBuildId/);
  });

  it("never reads the expected build id straight from the environment", () => {
    // process.env.P3_EXPECTED_BUILD_ID would be optional again: unset would silently skip
    // the comparison rather than refuse the run.
    expect(spec).not.toMatch(/process\.env\.P3_EXPECTED_BUILD_ID/);
  });

  it("still checks the build id before touching any certification account", () => {
    const buildCheck = spec.indexOf("buildId === run.expectedBuildId");
    const firstCertUse = spec.indexOf('for (const role of ["CERT_A", "CERT_B"]');
    expect(buildCheck).toBeGreaterThan(-1);
    expect(firstCertUse).toBeGreaterThan(-1);
    expect(buildCheck).toBeLessThan(firstCertUse);
  });
});
