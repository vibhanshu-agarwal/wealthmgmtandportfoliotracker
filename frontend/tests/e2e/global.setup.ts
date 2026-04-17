/**
 * Playwright setup project — authenticates once and saves browser storageState.
 *
 * The app now uses localStorage-backed auth session (`wmpt.auth.session`),
 * so we mint session state via `/api/auth/login` and persist it to storage.
 */

import { test as setup } from "@playwright/test";
import path from "node:path";
import { mintJwt } from "./helpers/auth";

const authFile = path.join(__dirname, "../../playwright/.auth/user.json");
const AUTH_STORAGE_KEY = "wmpt.auth.session";
const BASE_URL = "http://localhost:3000";
const SKIP_BACKEND_HEALTH_CHECK = process.env.SKIP_BACKEND_HEALTH_CHECK === "true";

setup("authenticate", async ({ request, page }) => {
  let payload: {
    token: string;
    userId: string;
    email: string;
    name: string;
  };
  if (SKIP_BACKEND_HEALTH_CHECK) {
    // CI smoke mode runs without backend services. Seed localStorage directly.
    payload = {
      token: mintJwt("user-001"),
      userId: "user-001",
      email: "dev@localhost.local",
      name: "Dev User",
    };
    console.log("[setup] Backend disabled; seeded synthetic local auth session");
  } else {
    const response = await request.post(`${BASE_URL}/api/auth/login`, {
      data: {
        email: "dev@localhost.local",
        password: "password",
      },
    });

    console.log(`[setup] Login API response: ${response.status()}`);

    if (!response.ok()) {
      const body = await response.text();
      throw new Error(`Backend login failed (${response.status()}): ${body}`);
    }

    payload = (await response.json()) as {
      token: string;
      userId: string;
      email: string;
      name: string;
    };
  }

  await page.goto(`${BASE_URL}/login`);
  await page.evaluate(
    ({ key, value }) => {
      window.localStorage.setItem(key, JSON.stringify(value));
    },
    { key: AUTH_STORAGE_KEY, value: payload },
  );

  await page.context().storageState({ path: authFile });
  console.log(`[setup] Auth state saved to ${authFile}`);
});
