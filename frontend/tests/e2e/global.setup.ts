/**
 * Playwright Global Setup — authenticates once via API and saves session state.
 *
 * Uses Better Auth's sign-in API endpoint directly (no UI interaction needed).
 * This avoids the React hydration race condition entirely — the API sets
 * session cookies on the response, and storageState captures them.
 */

import { test as setup } from "@playwright/test";
import path from "node:path";

const authFile = path.join(__dirname, "../../playwright/.auth/user.json");

setup("authenticate", async ({ request }) => {
  // POST directly to Better Auth's sign-in endpoint — bypasses the UI entirely
  const response = await request.post("http://localhost:3000/api/auth/sign-in/email", {
    data: {
      email: "dev@localhost.local",
      password: "password",
    },
  });

  console.log(`[setup] Sign-in API response: ${response.status()}`);

  if (!response.ok()) {
    const body = await response.text();
    throw new Error(`Better Auth sign-in failed (${response.status()}): ${body}`);
  }

  // Save cookies set by Better Auth for all dependent test projects
  await request.storageState({ path: authFile });
  console.log(`[setup] Auth state saved to ${authFile}`);
});
