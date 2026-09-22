/**
 * Phase 3 multi-user suite — run configuration and target guards.
 *
 * Origins are fixed per target; there is no override variable. Production mode refuses
 * to start without the owner's approval phrase, a declared identity lifecycle, the
 * certification-account credentials, an explicit email domain and a work directory
 * outside the repository. Problems are fixed codes and never echo environment values.
 */
import { randomBytes } from "node:crypto";
import path from "node:path";

export type TargetMode = "local" | "production";

export const TARGET_ORIGINS: Readonly<Record<TargetMode, { frontend: string; api: string }>> = Object.freeze({
  local: Object.freeze({ frontend: "http://localhost:3000", api: "http://localhost:8080" }),
  production: Object.freeze({
    frontend: "https://vibhanshu-ai-portfolio.dev",
    api: "https://api.vibhanshu-ai-portfolio.dev",
  }),
});

export const PRODUCTION_APPROVAL_PHRASE = "owner-approved-phase3-production-run";
export const PRODUCTION_MIN_AUTH_INTERVAL_MS = 13_000;
/** Production strict bucket (insights, chat): burst 30, 6 tokens per request, 1 token/s, per user. */
export const PRODUCTION_STRICT_INTERVAL_MS = 6_500;
/** In-suite fault injections (local only). NC5 (filtered run) and NC6 (unfixed build) are run-level. */
export const NEGATIVE_CONTROLS = ["NC1", "NC2", "NC3", "NC4", "NC7", "NC8", "NC9"] as const;
export type NegativeControl = (typeof NEGATIVE_CONTROLS)[number];

export interface Credentials {
  readonly email: string;
  readonly password: string;
}

export interface RunConfig {
  readonly mode: TargetMode;
  readonly frontend: string;
  readonly api: string;
  readonly runId: string;
  readonly workDir: string;
  readonly emailDomain: string;
  readonly authMinIntervalMs: number;
  readonly strictMinIntervalMs: number;
  readonly certA: Credentials;
  readonly certB: Credentials;
  readonly fresh: Credentials;
  readonly allowProvision: boolean;
  readonly negativeControl: NegativeControl | null;
  readonly skipChat: boolean;
}

export type ConfigProblem =
  | "TARGET_INVALID"
  | "DEBUG_SET"
  | "APPROVAL_MISSING"
  | "LIFECYCLE_NOT_DECLARED"
  | "EMAIL_DOMAIN_MISSING"
  | "CERT_A_CREDENTIALS_MISSING"
  | "CERT_B_CREDENTIALS_MISSING"
  | "WORK_DIR_INVALID"
  | "NEGATIVE_CONTROL_IN_PRODUCTION"
  | "NEGATIVE_CONTROL_UNKNOWN"
  | "AUTH_INTERVAL_TOO_LOW"
  | "AUTH_INTERVAL_INVALID"
  | "RUN_ID_INVALID";

export type ConfigResult =
  | { readonly ok: true; readonly config: RunConfig }
  | { readonly ok: false; readonly problems: readonly ConfigProblem[] };

type Env = Readonly<Record<string, string | undefined>>;

interface ResolveOptions {
  readonly repoRoot: string;
  readonly now: Date;
  readonly randomHex: () => string;
}

const DOMAIN_PATTERN = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i;
const RUN_ID_PATTERN = /^p3-\d{8}T\d{6}Z-[0-9a-f]{4}$/;

function present(value: string | undefined): value is string {
  return typeof value === "string" && value.length > 0;
}

function isOutside(candidate: string, root: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  return relative.startsWith("..") || path.isAbsolute(relative);
}

function randomPassword(): string {
  return randomBytes(18).toString("base64url").slice(0, 24);
}

function compactUtc(now: Date): string {
  return now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

export function resolveRunConfig(env: Env, options: ResolveOptions): ConfigResult {
  const problems: ConfigProblem[] = [];
  const target = env.P3_TARGET;
  if (target !== "local" && target !== "production") {
    return { ok: false, problems: ["TARGET_INVALID"] };
  }
  const mode: TargetMode = target;
  const production = mode === "production";

  if (present(env.DEBUG) || present(env.PWDEBUG)) problems.push("DEBUG_SET");

  const workDir = env.P3_WORK_DIR;
  if (!present(workDir) || !path.isAbsolute(workDir) || !isOutside(workDir, options.repoRoot)) {
    problems.push("WORK_DIR_INVALID");
  }

  let negativeControl: NegativeControl | null = null;
  if (present(env.P3_NEGATIVE_CONTROL)) {
    if (production) {
      problems.push("NEGATIVE_CONTROL_IN_PRODUCTION");
    } else if ((NEGATIVE_CONTROLS as readonly string[]).includes(env.P3_NEGATIVE_CONTROL)) {
      negativeControl = env.P3_NEGATIVE_CONTROL as NegativeControl;
    } else {
      problems.push("NEGATIVE_CONTROL_UNKNOWN");
    }
  }

  let authMinIntervalMs = production ? PRODUCTION_MIN_AUTH_INTERVAL_MS : 0;
  if (present(env.P3_AUTH_MIN_INTERVAL_MS)) {
    const parsed = Number(env.P3_AUTH_MIN_INTERVAL_MS);
    if (!Number.isInteger(parsed) || parsed < 0) {
      problems.push("AUTH_INTERVAL_INVALID");
    } else if (production && parsed < PRODUCTION_MIN_AUTH_INTERVAL_MS) {
      problems.push("AUTH_INTERVAL_TOO_LOW");
    } else {
      authMinIntervalMs = parsed;
    }
  }

  let runId = `p3-${compactUtc(options.now)}-${options.randomHex()}`;
  if (present(env.P3_RUN_ID)) {
    if (RUN_ID_PATTERN.test(env.P3_RUN_ID)) runId = env.P3_RUN_ID;
    else problems.push("RUN_ID_INVALID");
  }
  let emailDomain = "example.test";
  let certA: Credentials;
  let certB: Credentials;

  if (production) {
    if (env.P3_PRODUCTION_APPROVAL !== PRODUCTION_APPROVAL_PHRASE) problems.push("APPROVAL_MISSING");
    if (env.P3_IDENTITY_LIFECYCLE !== "retained") problems.push("LIFECYCLE_NOT_DECLARED");
    if (!present(env.P3_EMAIL_DOMAIN) || !DOMAIN_PATTERN.test(env.P3_EMAIL_DOMAIN)) {
      problems.push("EMAIL_DOMAIN_MISSING");
    } else {
      emailDomain = env.P3_EMAIL_DOMAIN;
    }
    if (!present(env.P3_CERT_A_EMAIL) || !present(env.P3_CERT_A_PASSWORD)) problems.push("CERT_A_CREDENTIALS_MISSING");
    if (!present(env.P3_CERT_B_EMAIL) || !present(env.P3_CERT_B_PASSWORD)) problems.push("CERT_B_CREDENTIALS_MISSING");
    certA = { email: env.P3_CERT_A_EMAIL ?? "", password: env.P3_CERT_A_PASSWORD ?? "" };
    certB = { email: env.P3_CERT_B_EMAIL ?? "", password: env.P3_CERT_B_PASSWORD ?? "" };
  } else {
    if (present(env.P3_EMAIL_DOMAIN) && DOMAIN_PATTERN.test(env.P3_EMAIL_DOMAIN)) emailDomain = env.P3_EMAIL_DOMAIN;
    const suffix = runId.slice(3).toLowerCase();
    certA = present(env.P3_CERT_A_EMAIL) && present(env.P3_CERT_A_PASSWORD)
      ? { email: env.P3_CERT_A_EMAIL, password: env.P3_CERT_A_PASSWORD }
      : { email: `p3-cert-a-${suffix}@${emailDomain}`, password: randomPassword() };
    certB = present(env.P3_CERT_B_EMAIL) && present(env.P3_CERT_B_PASSWORD)
      ? { email: env.P3_CERT_B_EMAIL, password: env.P3_CERT_B_PASSWORD }
      : { email: `p3-cert-b-${suffix}@${emailDomain}`, password: randomPassword() };
  }

  const fresh: Credentials = {
    email: `p3-fresh-${runId.slice(3).toLowerCase()}@${emailDomain}`,
    password: present(env.P3_FRESH_PASSWORD) ? env.P3_FRESH_PASSWORD : randomPassword(),
  };

  if (problems.length > 0) return { ok: false, problems };

  const origins = TARGET_ORIGINS[mode];
  return {
    ok: true,
    config: {
      mode,
      frontend: origins.frontend,
      api: origins.api,
      runId,
      workDir: path.resolve(workDir as string),
      emailDomain,
      authMinIntervalMs,
      strictMinIntervalMs: production ? PRODUCTION_STRICT_INTERVAL_MS : 0,
      certA,
      certB,
      fresh,
      allowProvision: !production,
      negativeControl,
      skipChat: env.P3_SKIP_CHAT === "1",
    },
  };
}
