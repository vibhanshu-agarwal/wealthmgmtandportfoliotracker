/**
 * Playwright setup project — authenticates once and saves browser storageState.
 *
 * The app uses localStorage-backed auth session (`wmpt.auth.session`).
 * We use addInitScript so the session is present before the first document load,
 * then persist storageState for dependent projects.
 */

import { test as setup } from "@playwright/test";
import path from "node:path";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

const authFile = path.join(__dirname, "../../playwright/.auth/user.json");
const BASE_URL = "http://localhost:3000";

setup("authenticate", async ({ request, page }) => {
  await installGatewaySessionInitScript(page, request);

  await page.goto(`${BASE_URL}/overview`);
  await page.context().storageState({ path: authFile });
  console.log(`[setup] Auth state saved to ${authFile}`);
});
