// @vitest-environment node
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  DEFAULT_ACTION_TIMEOUT_MS,
  DEMO_EMAIL,
  DEMO_USER_ID,
  ENV,
  MAX_ACTION_TIMEOUT_MS,
  MAX_TEST_TIMEOUT_MS,
  MIN_ACTION_TIMEOUT_MS,
  MIN_TEST_TIMEOUT_MS,
  ORCHESTRATOR_DEADLINE_MS,
  parseActionTimeoutMs,
  readStepB5bEnvironment,
  resolveActionTimeoutMsForConfig,
  resolveTestTimeoutMs,
  TEST_TIMEOUT_ACTION_TIMEOUTS,
  WORST_CASE_PRE_CHILD_MS,
  type EnvSource,
} from "../../../production-e2e/lib/environment";
import { STEP_B_5B_ALLOWLIST } from "../../../production-e2e/lib/target-allowlist";

const SENTINEL_TOKEN = "eyJhbGciOiJIUzI1NiJ9.SENTINEL-TOKEN-PAYLOAD.SENTINEL-SIGNATURE"; // fake fixture, gitleaks:allow
const WORK = path.resolve("/step-b-5b-test/work");
const GOLDEN = path.resolve("/step-b-5b-test/golden.json");

const validEnv = (overrides: Record<string, string | undefined> = {}): EnvSource => ({
  [ENV.FRONTEND_URL]: STEP_B_5B_ALLOWLIST.frontend,
  [ENV.API_URL]: STEP_B_5B_ALLOWLIST.api,
  [ENV.TOKEN]: SENTINEL_TOKEN,
  [ENV.EMAIL]: DEMO_EMAIL,
  [ENV.USER_ID]: DEMO_USER_ID,
  [ENV.WORK_DIR]: WORK,
  [ENV.GOLDEN_FILE]: GOLDEN,
  [ENV.BASELINE_VERSION]: "12",
  [ENV.ALLOW_ACTIVE_PRESENCE]: "0",
  [ENV.ACTION_TIMEOUT_MS]: "45000",
  ...overrides,
});

describe("Step B 5b environment contract", () => {
  it("pins the public demo identity", () => {
    expect(DEMO_EMAIL).toBe("demo@wealthtracker.dev");
    expect(DEMO_USER_ID).toBe("00000000-0000-0000-0000-0000000d3110");
  });

  it("parses a complete, valid environment", () => {
    const result = readStepB5bEnvironment(validEnv());
    expect(result).toEqual({
      ok: true,
      config: {
        frontendOrigin: "https://vibhanshu-ai-portfolio.dev",
        apiOrigin: "https://api.vibhanshu-ai-portfolio.dev",
        token: SENTINEL_TOKEN,
        email: DEMO_EMAIL,
        userId: DEMO_USER_ID,
        workDir: WORK,
        goldenFile: GOLDEN,
        baselineVersion: 12,
        allowActivePresence: false,
        actionTimeoutMs: 45_000,
      },
    });
  });

  it("does not read process.env: only the object it is given counts", () => {
    const saved = process.env[ENV.TOKEN];
    process.env[ENV.TOKEN] = SENTINEL_TOKEN;
    try {
      const result = readStepB5bEnvironment({});
      expect(result.ok).toBe(false);
    } finally {
      if (saved === undefined) delete process.env[ENV.TOKEN];
      else process.env[ENV.TOKEN] = saved;
    }
  });

  it("refuses non-allowlisted origins", () => {
    for (const overrides of [
      { [ENV.FRONTEND_URL]: "http://localhost:3000" },
      { [ENV.API_URL]: "http://localhost:8080" },
      { [ENV.FRONTEND_URL]: "https://vibhanshu-ai-portfolio.dev@evil.example" },
      { [ENV.API_URL]: STEP_B_5B_ALLOWLIST.frontend },
    ]) {
      const result = readStepB5bEnvironment(validEnv(overrides));
      expect(result.ok).toBe(false);
    }
    expect(readStepB5bEnvironment(validEnv({ [ENV.FRONTEND_URL]: "http://localhost:3000" }))).toMatchObject({
      problems: ["TARGET_FRONTEND_NOT_ALLOWLISTED"],
    });
  });

  it("accepts an injected allowlist so tests never need a production origin", () => {
    const injected = { frontend: "https://front.test", api: "https://api.test" };
    const result = readStepB5bEnvironment(
      validEnv({ [ENV.FRONTEND_URL]: "https://front.test", [ENV.API_URL]: "https://api.test" }),
      injected,
    );
    expect(result.ok).toBe(true);
    expect(readStepB5bEnvironment(validEnv(), injected).ok).toBe(false);
  });

  it("refuses to run as any identity but the public demo account", () => {
    expect(readStepB5bEnvironment(validEnv({ [ENV.USER_ID]: "00000000-0000-0000-0000-000000000e2e" }))).toMatchObject({
      ok: false,
      problems: ["USER_ID_NOT_DEMO_IDENTITY"],
    });
    expect(readStepB5bEnvironment(validEnv({ [ENV.EMAIL]: "someone@example.com" }))).toMatchObject({
      ok: false,
      problems: ["EMAIL_NOT_DEMO_IDENTITY"],
    });
  });

  it.each([
    ["missing", undefined],
    ["empty", ""],
    ["with a space", "abc def"],
    ["with a newline", "abc\ndef"],
    ["non-ASCII", "töken"],
  ])("refuses a token that is %s", (_label, token) => {
    expect(readStepB5bEnvironment(validEnv({ [ENV.TOKEN]: token }))).toMatchObject({
      ok: false,
      problems: ["TOKEN_MISSING_OR_MALFORMED"],
    });
  });

  it("never puts the token, or any other value, into a refusal", () => {
    const result = readStepB5bEnvironment(
      validEnv({
        [ENV.FRONTEND_URL]: "https://evil.example/leaky-path",
        [ENV.USER_ID]: "not-the-demo-user-LEAKY",
        [ENV.TOKEN]: `${SENTINEL_TOKEN} with space`,
        [ENV.WORK_DIR]: "relative/LEAKY-dir",
      }),
    );
    expect(result.ok).toBe(false);
    const text = JSON.stringify(result);
    for (const leaked of ["SENTINEL", "leaky-path", "LEAKY", "evil.example"]) {
      expect(text).not.toContain(leaked);
    }
  });

  it("requires absolute work and golden paths, and exposes the work dir only when it is usable", () => {
    const relativeWork = readStepB5bEnvironment(validEnv({ [ENV.WORK_DIR]: "work" }));
    expect(relativeWork).toMatchObject({ ok: false, problems: ["WORK_DIR_NOT_ABSOLUTE"], workDir: null });
    const relativeGolden = readStepB5bEnvironment(validEnv({ [ENV.GOLDEN_FILE]: "golden.json" }));
    expect(relativeGolden).toMatchObject({ ok: false, problems: ["GOLDEN_FILE_NOT_ABSOLUTE"], workDir: WORK });
    expect(readStepB5bEnvironment(validEnv({ [ENV.WORK_DIR]: undefined }))).toMatchObject({ ok: false, workDir: null });
  });

  it.each([
    ["1", 1],
    ["20", 20],
    ["100000", 100_000],
  ])("supplies BASELINE_VERSION %s as the integer %i", (raw, expected) => {
    const result = readStepB5bEnvironment(validEnv({ [ENV.BASELINE_VERSION]: raw }));
    expect(result.ok && result.config.baselineVersion).toBe(expected);
  });

  it.each([undefined, "", "abc", "-1", "1.5", "01", " 5", "5 ", "1e3", "0x10", "12\n", "99999999999999999999"])(
    "refuses the start when BASELINE_VERSION is %j (missing or malformed)",
    (raw) => {
      const result = readStepB5bEnvironment(validEnv({ [ENV.BASELINE_VERSION]: raw }));
      expect(result).toMatchObject({ ok: false, problems: ["BASELINE_VERSION_MISSING_OR_MALFORMED"] });
    },
  );

  it("refuses a missing baseline even when the variable is absent from the object altogether", () => {
    const env: Record<string, string | undefined> = { ...validEnv() };
    delete env[ENV.BASELINE_VERSION];
    expect(readStepB5bEnvironment(env)).toMatchObject({ ok: false, problems: ["BASELINE_VERSION_MISSING_OR_MALFORMED"] });
  });

  it("keeps the ledger directory usable for the refusal record and reports the baseline problem with the others", () => {
    const result = readStepB5bEnvironment(
      validEnv({ [ENV.BASELINE_VERSION]: "abc", [ENV.ACTION_TIMEOUT_MS]: "5" }),
    );
    expect(result).toMatchObject({
      ok: false,
      workDir: WORK,
      problems: ["ACTION_TIMEOUT_INVALID", "BASELINE_VERSION_MISSING_OR_MALFORMED"],
    });
  });

  it("never echoes a malformed baseline value in the refusal", () => {
    const result = readStepB5bEnvironment(validEnv({ [ENV.BASELINE_VERSION]: "LEAKY-baseline" }));
    expect(result.ok).toBe(false);
    expect(JSON.stringify(result)).not.toContain("LEAKY");
  });

  it("accepts version 0 as a supplied baseline", () => {
    const result = readStepB5bEnvironment(validEnv({ [ENV.BASELINE_VERSION]: "0" }));
    expect(result.ok && result.config.baselineVersion).toBe(0);
  });

  it("opts in to an active presence only for an exact 1", () => {
    for (const [raw, expected] of [["1", true], ["0", false], [undefined, false], ["", false], ["true", false], ["yes", false]] as const) {
      const result = readStepB5bEnvironment(validEnv({ [ENV.ALLOW_ACTIVE_PRESENCE]: raw }));
      expect(result.ok && result.config.allowActivePresence).toBe(expected);
    }
  });

  it("refuses when Playwright protocol debugging is enabled, because it would print the init-script token", () => {
    for (const overrides of [{ DEBUG: "pw:api" }, { DEBUG: "*" }, { PWDEBUG: "1" }, { DEBUG: "express,pw:protocol" }]) {
      expect(readStepB5bEnvironment({ ...validEnv(), ...overrides })).toMatchObject({
        ok: false,
        problems: ["PLAYWRIGHT_DEBUG_ENV_SET"],
      });
    }
    expect(readStepB5bEnvironment({ ...validEnv(), DEBUG: "" }).ok).toBe(true);
    expect(readStepB5bEnvironment({ ...validEnv(), DEBUG: "express:router" }).ok).toBe(true);
  });
});

describe("Step B 5b action timeout", () => {
  it("defaults to 60000 when unset or empty", () => {
    expect(DEFAULT_ACTION_TIMEOUT_MS).toBe(60_000);
    expect(parseActionTimeoutMs(undefined)).toEqual({ ok: true, value: 60_000 });
    expect(parseActionTimeoutMs("")).toEqual({ ok: true, value: 60_000 });
  });

  it("accepts integers within bounds", () => {
    expect(parseActionTimeoutMs("1000")).toEqual({ ok: true, value: MIN_ACTION_TIMEOUT_MS });
    expect(parseActionTimeoutMs("600000")).toEqual({ ok: true, value: MAX_ACTION_TIMEOUT_MS });
    expect(parseActionTimeoutMs("90000")).toEqual({ ok: true, value: 90_000 });
  });

  it.each(["999", "600001", "abc", "-1", "1.5", "60000ms", " 60000", "1e5", "0"])("rejects %j", (raw) => {
    expect(parseActionTimeoutMs(raw)).toEqual({ ok: false });
    expect(readStepB5bEnvironment(validEnv({ [ENV.ACTION_TIMEOUT_MS]: raw }))).toMatchObject({
      ok: false,
      problems: ["ACTION_TIMEOUT_INVALID"],
    });
  });

  it("gives the Playwright config a safe fallback instead of throwing on a bad value", () => {
    expect(resolveActionTimeoutMsForConfig(undefined)).toBe(60_000);
    expect(resolveActionTimeoutMsForConfig("garbage")).toBe(60_000);
    expect(resolveActionTimeoutMsForConfig("30000")).toBe(30_000);
  });
});

describe("Step B 5b test-level timeout", () => {
  /** The action timeout scripts/verify_step_b_5b.py passes by default (spec section 3, --action-timeout-ms). */
  const ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS = 120_000;
  const LEGS = 10;

  it("comfortably exceeds ten legs at the orchestrator's default action timeout, so it cannot cut the body off before finish()", () => {
    const timeout = resolveTestTimeoutMs(ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS);
    const tenLegs = LEGS * ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS;
    expect(tenLegs).toBe(1_200_000);
    // Ten legs, then two out-of-page reads, the quiet windows and the pacing waits, each up to one action timeout,
    // and headroom on top of that: a full half again over the legs alone.
    expect(timeout).toBeGreaterThanOrEqual(tenLegs + 2 * ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS);
    expect(timeout).toBeGreaterThanOrEqual(1.5 * tenLegs);
    expect(timeout).toBe(TEST_TIMEOUT_ACTION_TIMEOUTS * ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS);
    expect(timeout).toBe(1_800_000);
  });

  it.each([
    [MIN_ACTION_TIMEOUT_MS, MIN_TEST_TIMEOUT_MS],
    [20_000, MIN_TEST_TIMEOUT_MS],
    [30_000, 450_000],
    [60_000, 900_000],
    [90_000, 1_350_000],
    [120_000, 1_800_000],
    [300_000, MAX_TEST_TIMEOUT_MS],
    [MAX_ACTION_TIMEOUT_MS, MAX_TEST_TIMEOUT_MS],
  ])("is %i ms of action timeout -> %i ms of test timeout (15 action timeouts, floored and capped)", (action, expected) => {
    expect(resolveTestTimeoutMs(action)).toBe(expected);
  });

  it("is never below the floor, never decreases as the action timeout grows, and never exceeds the cap", () => {
    let previous = 0;
    for (let action = MIN_ACTION_TIMEOUT_MS; action <= MAX_ACTION_TIMEOUT_MS; action += 1_000) {
      const timeout = resolveTestTimeoutMs(action);
      expect(timeout).toBeGreaterThanOrEqual(MIN_TEST_TIMEOUT_MS);
      expect(timeout).toBeGreaterThanOrEqual(previous);
      expect(timeout).toBeLessThanOrEqual(MAX_TEST_TIMEOUT_MS);
      previous = timeout;
    }
  });

  it("fires before the orchestrator's own kill deadline, even when the child started as late as it possibly can", () => {
    expect(ORCHESTRATOR_DEADLINE_MS).toBe(2_700_000);
    expect(MAX_TEST_TIMEOUT_MS).toBe(ORCHESTRATOR_DEADLINE_MS - WORST_CASE_PRE_CHILD_MS);
    expect(MAX_TEST_TIMEOUT_MS).toBe(1_800_000);
    for (const action of [MIN_ACTION_TIMEOUT_MS, 60_000, ORCHESTRATOR_DEFAULT_ACTION_TIMEOUT_MS, MAX_ACTION_TIMEOUT_MS]) {
      expect(resolveTestTimeoutMs(action) + WORST_CASE_PRE_CHILD_MS).toBeLessThanOrEqual(ORCHESTRATOR_DEADLINE_MS);
    }
  });

  it("covers the slowest start the spec allows: a login below its 165 s guard, the baseline read and six 90 s warm-up probes", () => {
    const slowestStartMs = 165_000 + 30_000 + 6 * 90_000;
    expect(slowestStartMs).toBeLessThanOrEqual(WORST_CASE_PRE_CHILD_MS);
  });
});
