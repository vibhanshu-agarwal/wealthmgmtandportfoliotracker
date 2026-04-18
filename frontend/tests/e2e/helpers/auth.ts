import { createHmac } from "node:crypto";
import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";

/** GitHub Actions can set secrets to empty; `??` does not treat "" as missing. */
const AUTH_JWT_SECRET = (() => {
  const s = process.env.AUTH_JWT_SECRET?.trim();
  return s && s.length > 0 ? s : "local-dev-secret-change-me-min-32-chars";
})();

/** Low-level helper: sign a header.payload string with HS256. */
function signJwt(headerB64: string, payloadB64: string): string {
  const sig = createHmac("sha256", AUTH_JWT_SECRET)
    .update(`${headerB64}.${payloadB64}`)
    .digest("base64url");
  return `${headerB64}.${payloadB64}.${sig}`;
}

/**
 * Mint a HS256 JWT for use as an API Gateway Bearer token.
 *
 * Uses "user-001" as the default sub claim — this matches the Flyway V3 seed
 * migration (user_id = 'user-001') and the Better Auth dev user seed.
 */
export function mintJwt(userId = "user-001"): string {
  const header = Buffer.from(
    JSON.stringify({ alg: "HS256", typ: "JWT" }),
  ).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(
    JSON.stringify({
      sub: userId,
      name: "Dev User",
      email: "dev@localhost.local",
      iat: now,
      exp: now + 3600,
    }),
  ).toString("base64url");
  return signJwt(header, payload);
}

/**
 * Authenticate via the real Better Auth login form.
 *
 * Navigates to /login, fills in the credentials, submits the form, and waits
 * for the redirect to /overview. Better Auth handles session state cleanly
 * without the hydration issues that plagued NextAuth — no cookie injection
 * or CSRF token fetching needed.
 *
 * Credentials match the Better Auth dev user seed:
 *   email: "dev@localhost.local", password: "password"
 */
export async function injectAuthSession(page: Page): Promise<void> {
  await page.goto("http://localhost:3000/login");
  await page.waitForLoadState("networkidle");

  // Wait for React to hydrate the form — the submit button must be interactive
  // before clicking, otherwise the browser performs a native GET form submission
  // instead of the React onSubmit handler calling signIn.email().
  const submitButton = page.getByRole("button", { name: "Sign in" });
  await submitButton.waitFor({ state: "attached" });
  await page.waitForTimeout(300);

  await page.getByLabel("Email").fill("dev@localhost.local");
  await page.getByLabel("Password").fill("password");
  await submitButton.click();

  await expect(page.getByRole("heading", { level: 1 })).toBeVisible({
    timeout: 15_000,
  });
}
