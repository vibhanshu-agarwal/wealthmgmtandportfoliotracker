/**
 * Login credentials for the Golden-State E2E user
 * (`00000000-0000-0000-0000-000000000e2e`).
 *
 * Prefer `E2E_TEST_USER_EMAIL` / `E2E_TEST_USER_PASSWORD` from CI secrets.
 * Defaults match the Flyway-seeded E2E account used by the synthetic suites —
 * the same identity, not a second user.
 */
export function e2eLoginCredentials(): { email: string; password: string } {
  const email =
    process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
  const password =
    process.env.E2E_TEST_USER_PASSWORD ?? "e2e-test-password-2026";
  if (!email.trim() || !password) {
    throw new Error(
      "[e2e] E2E_TEST_USER_EMAIL and E2E_TEST_USER_PASSWORD must be non-empty " +
        "(or omit both to use the Golden-State seed defaults).",
    );
  }
  return { email, password };
}
