/**
 * Shared frozen-observation helpers for B1 Wave 5b seed callers (Tasks 5.4–5.6).
 *
 * Eligibility observation and write precondition are one value: callers login,
 * read authenticated GET /api/portfolio exactly once, freeze that numeric
 * version, and send it as `expectedVersion`. A 409 is never transient.
 */

export const FIXED_E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

const TRANSIENT_SEED_STATUSES = new Set([429, 500, 502, 503, 504]);

export type G5CallerMarker =
  | "synthetic-shell"
  | "global-setup"
  | "azure-api-smoke";

/**
 * Require an array with exactly one portfolio for `expectedUserId` whose
 * `version` is a non-negative safe integer, and return that version.
 */
export function selectPortfolioVersion(
  payload: unknown,
  expectedUserId: string,
): number {
  if (!Array.isArray(payload)) {
    throw new Error(
      "[portfolio-seed-version] GET /api/portfolio payload must be an array",
    );
  }

  const matches = payload.filter((item) => {
    if (item === null || typeof item !== "object") {
      return false;
    }
    return (item as { userId?: unknown }).userId === expectedUserId;
  });

  if (matches.length === 0) {
    throw new Error(
      `[portfolio-seed-version] expected exactly one portfolio for userId=${expectedUserId}, found 0`,
    );
  }
  if (matches.length > 1) {
    throw new Error(
      `[portfolio-seed-version] expected exactly one portfolio for userId=${expectedUserId}, found ${matches.length}`,
    );
  }

  const version = (matches[0] as { version?: unknown }).version;
  if (
    typeof version !== "number" ||
    !Number.isSafeInteger(version) ||
    version < 0
  ) {
    throw new Error(
      `[portfolio-seed-version] portfolio version must be a non-negative safe integer, got ${String(version)}`,
    );
  }

  return version;
}

/** Transport/cold-start statuses that may retry the same frozen body. Never 409. */
export function isTransientSeedStatus(status: number): boolean {
  return TRANSIENT_SEED_STATUSES.has(status);
}

/** First-response terminal conflict — log body once and fail; never retry. */
export function isTerminalVersionConflict(status: number): boolean {
  return status === 409;
}

export function formatG5Marker(
  caller: G5CallerMarker,
  expectedVersion: number,
): string {
  return `[b1-g5][${caller}] expectedVersion=${expectedVersion}`;
}

export function buildSeedBody(
  expectedVersion: number,
): { expectedVersion: number } {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error(
      `[portfolio-seed-version] cannot build seed body with invalid expectedVersion=${String(expectedVersion)}`,
    );
  }
  return { expectedVersion };
}
