/**
 * Provenance environment snapshot and the post-run secret scan.
 *
 * The snapshot records values only for an explicit allowlist of variable names and
 * presence booleans for secret-bearing names; nothing else from the environment is read.
 * The scan greps every text artifact outside pw-output/ (declared sensitive) for every
 * registered secret, as defense in depth behind the per-write SecretRegistry check.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const VALUE_ALLOWLIST = [
  "P3_TARGET",
  "P3_IDENTITY_LIFECYCLE",
  "P3_EMAIL_DOMAIN",
  "P3_AUTH_MIN_INTERVAL_MS",
  "P3_NEGATIVE_CONTROL",
  "P3_SKIP_CHAT",
  "CI",
] as const;

const PRESENCE_ONLY = [
  "P3_PRODUCTION_APPROVAL",
  "P3_CERT_A_EMAIL",
  "P3_CERT_A_PASSWORD",
  "P3_CERT_B_EMAIL",
  "P3_CERT_B_PASSWORD",
  "P3_FRESH_PASSWORD",
  "INTERNAL_API_KEY",
  "TF_VAR_internal_api_key",
  "DEBUG",
  "PWDEBUG",
] as const;

export interface ProvenanceEnvironment {
  readonly values: Record<string, string>;
  readonly present: Record<string, boolean>;
}

export function provenanceEnvironment(env: Readonly<Record<string, string | undefined>>): ProvenanceEnvironment {
  const values: Record<string, string> = {};
  for (const name of VALUE_ALLOWLIST) {
    const value = env[name];
    if (typeof value === "string" && value.length > 0) values[name] = value;
  }
  const present: Record<string, boolean> = {};
  for (const name of PRESENCE_ONLY) present[name] = typeof env[name] === "string" && (env[name] as string).length > 0;
  return { values, present };
}

const SKIPPED_DIRS = new Set(["pw-output"]);
const BINARY_EXTENSIONS = new Set([".png", ".jpg", ".jpeg", ".webm", ".zip"]);

export function scanArtifactsForSecrets(runDir: string, secrets: readonly string[]): Array<{ file: string }> {
  const hits: Array<{ file: string }> = [];
  const meaningful = secrets.filter((s) => s.length >= 6);
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const full = path.join(dir, entry);
      const relative = path.relative(runDir, full);
      if (statSync(full).isDirectory()) {
        if (!SKIPPED_DIRS.has(relative.split(path.sep)[0])) walk(full);
        continue;
      }
      if (BINARY_EXTENSIONS.has(path.extname(entry).toLowerCase())) continue;
      const text = readFileSync(full, "utf8");
      if (meaningful.some((secret) => text.includes(secret))) hits.push({ file: relative });
    }
  };
  walk(runDir);
  return hits;
}
