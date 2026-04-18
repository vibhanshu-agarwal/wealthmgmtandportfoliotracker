import type { APIRequestContext, Page } from "@playwright/test";
import { mintJwt } from "./auth";

const GATEWAY_URL = process.env.GATEWAY_BASE_URL ?? "http://localhost:8080";
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
  const skip = (process.env.SKIP_BACKEND_HEALTH_CHECK ?? "").toLowerCase() === "true";

  let payload: SessionPayload;
  if (skip) {
    payload = {
      token: mintJwt("user-001"),
      userId: "user-001",
      email: "dev@localhost.local",
      name: "Dev User",
    };
  } else {
    const res = await request.post(`${GATEWAY_URL}/api/auth/login`, {
      data: { email: "dev@localhost.local", password: "password" },
    });
    if (!res.ok()) {
      throw new Error(`[e2e] login failed: ${res.status()} ${await res.text()}`);
    }
    payload = (await res.json()) as SessionPayload;
  }

  await page.addInitScript(
    (opts: { key: string; value: SessionPayload }) => {
      window.localStorage.setItem(opts.key, JSON.stringify(opts.value));
    },
    { key: AUTH_STORAGE_KEY, value: payload },
  );
}
