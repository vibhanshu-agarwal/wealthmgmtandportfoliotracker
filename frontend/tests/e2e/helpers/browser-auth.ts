import type { APIRequestContext, Page } from "@playwright/test";
import { e2eLoginCredentials } from "./e2e-credentials";

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
 *
 * Identity is the Golden-State E2E user (`00000000-0000-0000-0000-000000000e2e`),
 * credentials from `E2E_TEST_USER_EMAIL` / `E2E_TEST_USER_PASSWORD` (see
 * `e2e-credentials.ts`). This must stay aligned with `helpers/api.ts`.
 */
export async function installGatewaySessionInitScript(
  page: Page,
  request: APIRequestContext,
): Promise<void> {
  void request;
  const credentials = e2eLoginCredentials();
  const res = await fetch(`${GATEWAY_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
  });
  if (!res.ok) {
    throw new Error(`[e2e] login failed: ${res.status} ${await res.text()}`);
  }
  const payload = (await res.json()) as SessionPayload;

  await page.addInitScript(
    (opts: { key: string; value: SessionPayload }) => {
      window.localStorage.setItem(opts.key, JSON.stringify(opts.value));
    },
    { key: AUTH_STORAGE_KEY, value: payload },
  );
}
