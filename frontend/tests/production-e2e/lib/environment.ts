/**
 * Wave 10.2 Step B (exit criterion 5b) - the browser child's input contract
 * (spec Appendix B), parsed from an injected environment object.
 *
 * Nothing here reads the process environment: the spec passes it in from inside
 * the test body, so importing this module (which `playwright test --list` does)
 * never touches the environment. Validation problems are fixed codes; a value,
 * and in particular the token, is never echoed back.
 */
import path from "node:path";
import { checkTargets, STEP_B_5B_ALLOWLIST, type TargetAllowlist } from "./target-allowlist";

export const ENV = {
  FRONTEND_URL: "STEP_B_5B_FRONTEND_URL",
  API_URL: "STEP_B_5B_API_URL",
  TOKEN: "STEP_B_5B_TOKEN",
  EMAIL: "STEP_B_5B_EMAIL",
  USER_ID: "STEP_B_5B_USER_ID",
  WORK_DIR: "STEP_B_5B_WORK_DIR",
  GOLDEN_FILE: "STEP_B_5B_GOLDEN_FILE",
  BASELINE_VERSION: "STEP_B_5B_BASELINE_VERSION",
  ALLOW_ACTIVE_PRESENCE: "STEP_B_5B_ALLOW_ACTIVE_PRESENCE",
  ACTION_TIMEOUT_MS: "STEP_B_5B_ACTION_TIMEOUT_MS",
} as const;

/**
 * The public demo identity (spec section 3). The browser refuses to run as any
 * other account, so a wrong token can never drive the mutating legs.
 */
export const DEMO_EMAIL = "demo@wealthtracker.dev";
export const DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";

export const DEFAULT_ACTION_TIMEOUT_MS = 60_000;
export const MIN_ACTION_TIMEOUT_MS = 1_000;
export const MAX_ACTION_TIMEOUT_MS = 600_000;

export interface StepB5bConfig {
  readonly frontendOrigin: string;
  readonly apiOrigin: string;
  /** Secret: never log, never record. */
  readonly token: string;
  readonly email: string;
  readonly userId: string;
  readonly workDir: string;
  readonly goldenFile: string;
  /**
   * The version read in P3: always a plain non-negative integer, because a missing or malformed
   * STEP_B_5B_BASELINE_VERSION refuses the start (BASELINE_VERSION_MISSING_OR_MALFORMED). Typed
   * nullable only so the mutation gate keeps its own defense-in-depth check.
   */
  readonly baselineVersion: number | null;
  readonly allowActivePresence: boolean;
  readonly actionTimeoutMs: number;
}

export type EnvironmentResult =
  | { readonly ok: true; readonly config: StepB5bConfig }
  | {
      readonly ok: false;
      /** Fixed problem codes, safe to print. */
      readonly problems: readonly string[];
      /** Usable ledger directory when the variable itself was well-formed, else null. */
      readonly workDir: string | null;
    };

export type EnvSource = Readonly<Record<string, string | undefined>>;

const TOKEN_SHAPE = /^[\x21-\x7E]{1,8192}$/;
const PLAIN_INTEGER = /^(0|[1-9]\d{0,14})$/;

/** Config-time and test-time shared parse of STEP_B_5B_ACTION_TIMEOUT_MS. */
export function parseActionTimeoutMs(raw: string | undefined): { ok: true; value: number } | { ok: false } {
  if (raw === undefined || raw === "") return { ok: true, value: DEFAULT_ACTION_TIMEOUT_MS };
  if (!/^\d{1,9}$/.test(raw)) return { ok: false };
  const value = Number(raw);
  if (value < MIN_ACTION_TIMEOUT_MS || value > MAX_ACTION_TIMEOUT_MS) return { ok: false };
  return { ok: true, value };
}

/**
 * For the Playwright config, which must load with no variables set (and must not
 * throw on a bad one): an unusable value falls back to the default, and the test
 * body then rejects it strictly through `readStepB5bEnvironment`.
 */
export function resolveActionTimeoutMsForConfig(raw: string | undefined): number {
  const parsed = parseActionTimeoutMs(raw);
  return parsed.ok ? parsed.value : DEFAULT_ACTION_TIMEOUT_MS;
}

/**
 * The orchestrator's overall deadline (spec 10.5), measured from the start of the login call.
 * The child is killed at this deadline and cleanup still runs, whatever the child was doing.
 */
export const ORCHESTRATOR_DEADLINE_MS = 2_700_000;
/**
 * The longest the child can be delayed before it starts: a login below its 165 s guard, the baseline read,
 * and up to six 90 s warm-up probes come to about 735 s (spec section 4, P2 and P3b), rounded up.
 */
export const WORST_CASE_PRE_CHILD_MS = 900_000;
/** Ten legs (each up to one action timeout) plus the two out-of-page reads, waits and quiet windows, with headroom. */
export const TEST_TIMEOUT_ACTION_TIMEOUTS = 15;
export const MIN_TEST_TIMEOUT_MS = 300_000;
/** The last moment a test-level timeout can fire and still precede the orchestrator's kill, even after the slowest start. */
export const MAX_TEST_TIMEOUT_MS = ORCHESTRATOR_DEADLINE_MS - WORST_CASE_PRE_CHILD_MS;

/**
 * The Playwright test-level timeout for the one spec, from the action timeout. It must outlast a slow but honest
 * run: a test timeout that fires while a leg is still working can cut the body off before `runner.finish()` and
 * leave legs missing. Fifteen action timeouts cover ten legs, the two independent reads, the quiet windows and the
 * pacing waits with headroom (at the orchestrator's default of 120 s that is 1,800 s, 1.5 times the ten legs alone),
 * floored at 300 s and capped so it fires before the orchestrator's own kill deadline could.
 */
export function resolveTestTimeoutMs(actionTimeoutMs: number): number {
  return Math.min(Math.max(actionTimeoutMs * TEST_TIMEOUT_ACTION_TIMEOUTS, MIN_TEST_TIMEOUT_MS), MAX_TEST_TIMEOUT_MS);
}

function isAbsoluteCleanPath(value: string | undefined): value is string {
  return value !== undefined && value !== "" && !value.includes("\0") && path.isAbsolute(value);
}

/** Protocol-level Playwright debugging would print init-script arguments (the token). */
function playwrightDebugEnabled(env: EnvSource): boolean {
  const debug = env.DEBUG ?? "";
  return (env.PWDEBUG ?? "") !== "" || debug.includes("pw") || debug.includes("*");
}

export function readStepB5bEnvironment(
  env: EnvSource,
  allowlist: TargetAllowlist = STEP_B_5B_ALLOWLIST,
): EnvironmentResult {
  const problems: string[] = [];

  const targets = checkTargets(env[ENV.FRONTEND_URL], env[ENV.API_URL], allowlist);
  if (!targets.ok) {
    for (const problem of targets.problems) problems.push(`TARGET_${problem}`);
  }

  const token = env[ENV.TOKEN];
  if (token === undefined || !TOKEN_SHAPE.test(token)) problems.push("TOKEN_MISSING_OR_MALFORMED");

  if (env[ENV.EMAIL] !== DEMO_EMAIL) problems.push("EMAIL_NOT_DEMO_IDENTITY");
  if (env[ENV.USER_ID] !== DEMO_USER_ID) problems.push("USER_ID_NOT_DEMO_IDENTITY");

  const workDirValue = env[ENV.WORK_DIR];
  const workDir = isAbsoluteCleanPath(workDirValue) ? workDirValue : null;
  if (workDir === null) problems.push("WORK_DIR_NOT_ABSOLUTE");

  const goldenFile = env[ENV.GOLDEN_FILE];
  if (!isAbsoluteCleanPath(goldenFile)) problems.push("GOLDEN_FILE_NOT_ABSOLUTE");

  const timeout = parseActionTimeoutMs(env[ENV.ACTION_TIMEOUT_MS]);
  if (!timeout.ok) problems.push("ACTION_TIMEOUT_INVALID");

  // Required (spec Appendix B): without the P3 baseline the mutating legs could never run, so
  // starting would only burn the deploy window against production and end in a misleading skip.
  const baselineRaw = env[ENV.BASELINE_VERSION];
  if (baselineRaw === undefined || !PLAIN_INTEGER.test(baselineRaw)) {
    problems.push("BASELINE_VERSION_MISSING_OR_MALFORMED");
  }

  if (playwrightDebugEnabled(env)) problems.push("PLAYWRIGHT_DEBUG_ENV_SET");

  if (problems.length > 0 || !targets.ok || token === undefined || workDir === null || !isAbsoluteCleanPath(goldenFile) || !timeout.ok) {
    return { ok: false, problems, workDir };
  }

  return {
    ok: true,
    config: {
      frontendOrigin: targets.frontend,
      apiOrigin: targets.api,
      token,
      email: DEMO_EMAIL,
      userId: DEMO_USER_ID,
      workDir,
      goldenFile,
      baselineVersion: Number(baselineRaw),
      // Only an exact "1" opts in; any other value keeps the safe default (abort before mutation).
      allowActivePresence: env[ENV.ALLOW_ACTIVE_PRESENCE] === "1",
      actionTimeoutMs: timeout.value,
    },
  };
}
