import path from "node:path";
import { describe, expect, it } from "vitest";
import { resolveRunConfig } from "../config";

const REPO_ROOT = path.resolve("C:/repo/wealth");
const OUTSIDE = path.resolve("C:/evidence/phase3");
const NOW = new Date("2026-09-22T10:11:12.000Z");

function resolve(env: Record<string, string | undefined>) {
  return resolveRunConfig(env, { repoRoot: REPO_ROOT, now: NOW, randomHex: () => "a1b2" });
}

const PRODUCTION_ENV = {
  P3_TARGET: "production",
  P3_PRODUCTION_APPROVAL: "owner-approved-phase3-production-run",
  P3_IDENTITY_LIFECYCLE: "retained",
  P3_EMAIL_DOMAIN: "certs.example.org",
  P3_WORK_DIR: OUTSIDE,
  P3_CERT_A_EMAIL: "cert-a@certs.example.org",
  P3_CERT_A_PASSWORD: "cert-a-password-long",
  P3_CERT_B_EMAIL: "cert-b@certs.example.org",
  P3_CERT_B_PASSWORD: "cert-b-password-long",
  // Supplied by the frontend-only deploy run that uploaded the build (B3).
  P3_EXPECTED_BUILD_ID: "xHLycg2EB2LnlAniJ-SbA",
};

function problemsOf(result: ReturnType<typeof resolve>) {
  return result.ok ? [] : result.problems;
}

describe("resolveRunConfig — target selection", () => {
  it("rejects a missing or unknown target", () => {
    expect(problemsOf(resolve({}))).toContain("TARGET_INVALID");
    expect(problemsOf(resolve({ P3_TARGET: "staging" }))).toContain("TARGET_INVALID");
    expect(problemsOf(resolve({ P3_TARGET: "Production" }))).toContain("TARGET_INVALID");
  });

  it("uses fixed origins per target with no override", () => {
    const local = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_FRONTEND_ORIGIN: "http://evil" });
    expect(local).toMatchObject({
      ok: true,
      config: { mode: "local", frontend: "http://localhost:3000", api: "http://localhost:8080" },
    });
    const prod = resolve(PRODUCTION_ENV);
    expect(prod).toMatchObject({
      ok: true,
      config: {
        mode: "production",
        frontend: "https://vibhanshu-ai-portfolio.dev",
        api: "https://api.vibhanshu-ai-portfolio.dev",
      },
    });
  });

  it("refuses to run with DEBUG or PWDEBUG set in any mode", () => {
    expect(problemsOf(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, DEBUG: "pw:api" }))).toContain("DEBUG_SET");
    expect(problemsOf(resolve({ ...PRODUCTION_ENV, PWDEBUG: "1" }))).toContain("DEBUG_SET");
  });
});

describe("resolveRunConfig — production preconditions", () => {
  it("accepts the complete production environment", () => {
    const result = resolve(PRODUCTION_ENV);
    expect(result.ok).toBe(true);
  });

  it.each([
    ["P3_PRODUCTION_APPROVAL", undefined, "APPROVAL_MISSING"],
    ["P3_PRODUCTION_APPROVAL", "owner-approved-phase3-production-run ", "APPROVAL_MISSING"],
    ["P3_PRODUCTION_APPROVAL", "yes", "APPROVAL_MISSING"],
    ["P3_IDENTITY_LIFECYCLE", undefined, "LIFECYCLE_NOT_DECLARED"],
    ["P3_IDENTITY_LIFECYCLE", "cleanup", "LIFECYCLE_NOT_DECLARED"],
    ["P3_EMAIL_DOMAIN", undefined, "EMAIL_DOMAIN_MISSING"],
    ["P3_EMAIL_DOMAIN", "not a domain", "EMAIL_DOMAIN_MISSING"],
    ["P3_CERT_A_EMAIL", undefined, "CERT_A_CREDENTIALS_MISSING"],
    ["P3_CERT_A_PASSWORD", "", "CERT_A_CREDENTIALS_MISSING"],
    ["P3_CERT_B_EMAIL", undefined, "CERT_B_CREDENTIALS_MISSING"],
    ["P3_CERT_B_PASSWORD", undefined, "CERT_B_CREDENTIALS_MISSING"],
    ["P3_WORK_DIR", undefined, "WORK_DIR_INVALID"],
    ["P3_WORK_DIR", "relative/dir", "WORK_DIR_INVALID"],
    ["P3_WORK_DIR", path.join(REPO_ROOT, "evidence"), "WORK_DIR_INVALID"],
    ["P3_NEGATIVE_CONTROL", "NC2", "NEGATIVE_CONTROL_IN_PRODUCTION"],
    ["P3_AUTH_MIN_INTERVAL_MS", "5000", "AUTH_INTERVAL_TOO_LOW"],
  ])("with %s=%j reports %s", (key, value, problem) => {
    expect(problemsOf(resolve({ ...PRODUCTION_ENV, [key]: value }))).toContain(problem);
  });

  it("never provisions in production even when asked", () => {
    const result = resolve({ ...PRODUCTION_ENV, P3_ALLOW_PROVISION: "1" });
    expect(result).toMatchObject({ ok: true, config: { allowProvision: false } });
  });

  it("defaults production auth spacing to 13 s and accepts a larger value", () => {
    expect(resolve(PRODUCTION_ENV)).toMatchObject({ ok: true, config: { authMinIntervalMs: 13000 } });
    expect(resolve({ ...PRODUCTION_ENV, P3_AUTH_MIN_INTERVAL_MS: "20000" })).toMatchObject({
      ok: true,
      config: { authMinIntervalMs: 20000 },
    });
  });

  it("never echoes credential values in problems", () => {
    const result = resolve({ ...PRODUCTION_ENV, P3_PRODUCTION_APPROVAL: "cert-a-password-long" });
    expect(JSON.stringify(result)).not.toContain("cert-a-password-long");
  });
});

describe("resolveRunConfig — local defaults", () => {
  it("provisions per-run certification accounts when none are supplied", () => {
    const result = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE });
    expect(result).toMatchObject({
      ok: true,
      config: {
        allowProvision: true,
        emailDomain: "example.test",
        authMinIntervalMs: 0,
        runId: "p3-20260922T101112Z-a1b2",
      },
    });
    if (!result.ok) throw new Error("unreachable");
    expect(result.config.certA.email).toBe("p3-cert-a-20260922t101112z-a1b2@example.test");
    expect(result.config.certB.email).toBe("p3-cert-b-20260922t101112z-a1b2@example.test");
    expect(result.config.certA.password).toHaveLength(24);
    expect(result.config.certA.password).not.toBe(result.config.certB.password);
  });

  it("accepts an allowed local negative control and rejects an unknown one", () => {
    expect(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_NEGATIVE_CONTROL: "NC3" })).toMatchObject({
      ok: true,
      config: { negativeControl: "NC3" },
    });
    for (const id of ["NC1", "NC2", "NC3", "NC4", "NC7", "NC8", "NC9", "NC10", "NC11"]) {
      expect(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_NEGATIVE_CONTROL: id })).toMatchObject({ ok: true });
    }
    // NC5 (filtered run) and NC6 (unfixed build) are run-level controls, not env faults.
    for (const id of ["NC5", "NC6", "NC12", "nc1"]) {
      expect(problemsOf(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_NEGATIVE_CONTROL: id }))).toContain(
        "NEGATIVE_CONTROL_UNKNOWN",
      );
    }
  });

  it("spaces strict-bucket (insights/chat) requests only in production", () => {
    expect(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE })).toMatchObject({ ok: true, config: { strictMinIntervalMs: 0 } });
    expect(resolve(PRODUCTION_ENV)).toMatchObject({ ok: true, config: { strictMinIntervalMs: 6500 } });
  });

  it("reuses an inherited P3_RUN_ID so every Playwright process agrees on the run", () => {
    const result = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_RUN_ID: "p3-20260101T000000Z-ffff" });
    expect(result).toMatchObject({ ok: true, config: { runId: "p3-20260101T000000Z-ffff" } });
    expect(problemsOf(resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_RUN_ID: "../escape" }))).toContain(
      "RUN_ID_INVALID",
    );
  });

  it("uses an inherited FRESH password and otherwise generates one", () => {
    const inherited = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_FRESH_PASSWORD: "inherited-fresh-pw-123" });
    expect(inherited).toMatchObject({ ok: true, config: { fresh: { password: "inherited-fresh-pw-123" } } });
    const generated = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE });
    if (!generated.ok) throw new Error("unreachable");
    expect(generated.config.fresh.email).toBe("p3-fresh-20260922t101112z-a1b2@example.test");
    expect(generated.config.fresh.password).toHaveLength(24);
  });

  it("still requires an absolute work dir outside the repository", () => {
    expect(problemsOf(resolve({ P3_TARGET: "local", P3_WORK_DIR: REPO_ROOT }))).toContain("WORK_DIR_INVALID");
  });
});

describe("resolveRunConfig — expected served build id (B3)", () => {
  // S00 compares the served build id against this value. It must come from the deploy run
  // that uploaded the build, never from the page being measured, and a Production run that
  // does not declare it must not start at all: the suite is not serial, so a later failure
  // would still be preceded by S02's permanent signup.
  const VALID = "xHLycg2EB2LnlAniJ-SbA";

  it("refuses a Production run that declares no expected build id", () => {
    const { P3_EXPECTED_BUILD_ID: _omitted, ...withoutExpectedId } = PRODUCTION_ENV;
    expect(problemsOf(resolve(withoutExpectedId))).toContain("EXPECTED_BUILD_ID_MISSING");
  });

  it("refuses a Production run whose expected build id is malformed", () => {
    for (const bad of ["", "   ", "short", "not a build id", "x".repeat(65)]) {
      expect(
        problemsOf(resolve({ ...PRODUCTION_ENV, P3_EXPECTED_BUILD_ID: bad })),
      ).toContain("EXPECTED_BUILD_ID_MISSING");
    }
  });

  it("accepts a Production run that declares a well-formed expected build id", () => {
    const result = resolve({ ...PRODUCTION_ENV, P3_EXPECTED_BUILD_ID: VALID });
    expect(result).toMatchObject({ ok: true, config: { expectedBuildId: VALID } });
  });

  it("does not require one for a local run, and reports none", () => {
    const result = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE });
    expect(problemsOf(result)).not.toContain("EXPECTED_BUILD_ID_MISSING");
    expect(result).toMatchObject({ ok: true, config: { expectedBuildId: null } });
  });

  it("still honours a declared expected build id in a local run", () => {
    const result = resolve({ P3_TARGET: "local", P3_WORK_DIR: OUTSIDE, P3_EXPECTED_BUILD_ID: VALID });
    expect(result).toMatchObject({ ok: true, config: { expectedBuildId: VALID } });
  });
});
