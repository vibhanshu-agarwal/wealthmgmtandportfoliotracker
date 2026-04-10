import { createHmac } from "node:crypto";
import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";

const AUTH_JWT_SECRET =
  process.env.AUTH_JWT_SECRET ?? "local-dev-secret-change-me-min-32-chars";

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
 * migration (user_id = 'user-001') and the credentials provider in auth.config.ts.
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
      email: "dev@local",
      iat: now,
      exp: now + 3600,
    }),
  ).toString("base64url");
  return signJwt(header, payload);
}

/**
 * Authenticate via the real NextAuth credentials login form.
 *
 * Navigates to /login, fills in the credentials, submits the form, and waits
 * for the redirect to /overview. This lets NextAuth set its own CSRF tokens and
 * HttpOnly session cookie — no interception or cookie injection needed.
 *
 * Credentials match the mock provider in auth.config.ts:
 *   username: "user-001", password: "password"
 *
 * These also match the Flyway V3 seed (user_id = 'user-001'), so all subsequent
 * API calls will find the seeded portfolio and holdings.
 */
export async function injectAuthSession(page: Page): Promise<void> {
  // Step 1: Get the CSRF token from NextAuth
  await page.goto("http://localhost:3000/api/auth/csrf");
  const csrfData = await page.evaluate(() => {
    try { return JSON.parse(document.body.innerText); } catch { return null; }
  });
  const csrfToken = csrfData?.csrfToken as string | undefined;
  console.log(`[auth] CSRF token: ${csrfToken ? csrfToken.slice(0, 20) + "…" : "NOT FOUND"}`);

  // Step 2: POST credentials directly to NextAuth sign-in endpoint
  await page.goto("http://localhost:3000/login");
  await page.waitForLoadState("networkidle");

  if (csrfToken) {
    // Direct POST to NextAuth credentials endpoint with CSRF token
    const response = await page.evaluate(
      async ({ token }) => {
        const res = await fetch("/api/auth/callback/credentials", {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            username: "user-001",
            password: "password",
            csrfToken: token,
            callbackUrl: "http://localhost:3000/overview",
            json: "true",
          }).toString(),
          redirect: "manual",
        });
        return { status: res.status, url: res.url };
      },
      { token: csrfToken },
    );
    console.log(`[auth] Credentials POST → status=${response.status} url=${response.url}`);
  }

  // Step 3: Hard reload to force server-side SSR hydration of the session.
  // The credentials POST sets the HttpOnly cookie, but the SPA state still
  // reflects the pre-login session. A full reload makes the server read the
  // new cookie via auth() and pass it to SessionProvider, so useSession()
  // returns "authenticated" immediately on first render.
  await page.reload({ waitUntil: "networkidle" });

  // Step 4: Navigate to /overview — confirmed authenticated landing page
  await page.goto("http://localhost:3000/overview");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 10_000 });
}
