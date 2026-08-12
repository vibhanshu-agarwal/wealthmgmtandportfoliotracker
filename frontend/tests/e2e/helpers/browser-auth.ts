import type { APIRequestContext, Page } from "@playwright/test";

const GATEWAY_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const AUTH_STORAGE_KEY = "wmpt.auth.session";

type SessionPayload = {
  token: string;
  userId: string;
  email: string;
  name: string;
};

/**
 * Registers an init script that writes the gateway login session into localStorage
 * before any frame loads. This avoids races where React reads auth before Playwright
 * storageState hydration completes on static-export pages.
 */
export async function installGatewaySessionInitScript(
  page: Page,
  request: APIRequestContext,
): Promise<void> {
  // Credentials match the dev user seeded by portfolio-service's V15 migration
  // (portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql) —
  // real per-user auth replaced the old app.auth.* hardcoded-credential default
  // (dev@localhost.local / password) that this used to rely on.
  const res = await request.post(`${GATEWAY_URL}/api/auth/login`, {
    data: { email: "dev@local", password: "local-dev-password-2026" },
  });
  if (!res.ok()) {
    throw new Error(`[e2e] login failed: ${res.status()} ${await res.text()}`);
  }
  const payload = (await res.json()) as SessionPayload;

  await page.addInitScript(
    (opts: { key: string; value: SessionPayload }) => {
      window.localStorage.setItem(opts.key, JSON.stringify(opts.value));
    },
    { key: AUTH_STORAGE_KEY, value: payload },
  );
}
