/**
 * Wave 10.2 Step B (exit criterion 5b) - exact origin allowlist.
 *
 * The verifier only ever talks to the two production origins below. There is no
 * override flag and no environment lookup: a candidate must be byte-for-byte equal
 * to the allowlisted origin string. That single rule rejects every other scheme,
 * host, port, path, query, fragment, casing, whitespace and userinfo trick
 * (for example "https://vibhanshu-ai-portfolio.dev@evil.example") without having
 * to reason about URL parsing edge cases. Unit tests inject their own allowlist
 * through the `allowlist` parameter.
 */

export interface TargetAllowlist {
  readonly frontend: string;
  readonly api: string;
}

export const STEP_B_5B_ALLOWLIST: TargetAllowlist = Object.freeze({
  frontend: "https://vibhanshu-ai-portfolio.dev",
  api: "https://api.vibhanshu-ai-portfolio.dev",
});

export type TargetCheckProblem = "FRONTEND_MISSING" | "FRONTEND_NOT_ALLOWLISTED" | "API_MISSING" | "API_NOT_ALLOWLISTED";

export type TargetCheck =
  | { readonly ok: true; readonly frontend: string; readonly api: string }
  | { readonly ok: false; readonly problems: readonly TargetCheckProblem[] };

/** True only when `candidate` is exactly the allowlisted origin string. */
export function isAllowlistedOrigin(candidate: unknown, expectedOrigin: string): boolean {
  return typeof candidate === "string" && candidate === expectedOrigin;
}

/**
 * Checks the two target origins. The frontend candidate must equal the allowlisted
 * frontend origin and the API candidate the allowlisted API origin; swapping them
 * is rejected. Problems are fixed codes and never echo the candidate values.
 */
export function checkTargets(
  frontendCandidate: string | undefined,
  apiCandidate: string | undefined,
  allowlist: TargetAllowlist = STEP_B_5B_ALLOWLIST,
): TargetCheck {
  const problems: TargetCheckProblem[] = [];

  if (frontendCandidate === undefined || frontendCandidate === "") {
    problems.push("FRONTEND_MISSING");
  } else if (!isAllowlistedOrigin(frontendCandidate, allowlist.frontend)) {
    problems.push("FRONTEND_NOT_ALLOWLISTED");
  }

  if (apiCandidate === undefined || apiCandidate === "") {
    problems.push("API_MISSING");
  } else if (!isAllowlistedOrigin(apiCandidate, allowlist.api)) {
    problems.push("API_NOT_ALLOWLISTED");
  }

  if (problems.length > 0) return { ok: false, problems };
  return { ok: true, frontend: allowlist.frontend, api: allowlist.api };
}
