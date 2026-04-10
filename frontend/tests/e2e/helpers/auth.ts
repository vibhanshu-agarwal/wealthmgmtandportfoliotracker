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
 * The sub claim must be a valid UUID matching a row in the users table.
 */
export function mintJwt(userId = "00000000-0000-0000-0000-000000000001"): string {
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
 * Mint a NextAuth session cookie JWT.
 *
 * NextAuth's session() callback reads token.__rawJwt to populate session.accessToken.
 * The __rawJwt value must be a valid API Gateway JWT (HS256, sub = UUID).
 * We mint the API JWT first, then embed it as __rawJwt in the session cookie JWT.
 */
function mintSessionCookieJwt(userId = "00000000-0000-0000-0000-000000000001"): string {
  const apiJwt = mintJwt(userId);

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
      // NextAuth reads this field in the session() callback to populate session.accessToken.
      // Without it, the frontend sends no Authorization header and the API returns $0.00.
      __rawJwt: apiJwt,
    }),
  ).toString("base64url");
  return signJwt(header, payload);
}

/**
 * Inject a pre-minted HS256 JWT as the NextAuth session cookie and navigate
 * to /overview to confirm the session is active.
 *
 * Uses cookie injection (same HS256 key as auth.ts encode/decode) rather than
 * the NextAuth signIn() UI flow, which is unreliable in CI due to timing issues.
 */
export async function injectAuthSession(page: Page): Promise<void> {
  const token = mintSessionCookieJwt("00000000-0000-0000-0000-000000000001");

  await page.context().addCookies([
    {
      name: "authjs.session-token",
      value: token,
      domain: "127.0.0.1",
      path: "/",
    },
  ]);

  await page.goto("http://127.0.0.1:3000/overview");

  // Wait for the session to be fully established — poll /api/auth/session until
  // accessToken is present. This ensures TanStack Query hooks fire with a valid token.
  await page.waitForFunction(async () => {
    const res = await fetch("/api/auth/session");
    const session = await res.json();
    return !!session?.accessToken;
  }, undefined, { timeout: 15000, polling: 500 });

  // Confirm the overview page rendered (dashboard shell is visible)
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
}
