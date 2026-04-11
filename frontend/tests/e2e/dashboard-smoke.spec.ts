import { expect, test } from "@playwright/test";

test("standalone build starts and renders dashboard overview", async ({ page }) => {
  await page.goto("/overview");

  // The "Overview" heading is rendered by the server component (page.tsx),
  // confirming the standalone build serves the route correctly.
  // OverviewPageContent is session-gated, so without auth it shows a skeleton
  // or redirects — we only assert the page-level heading here.
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
});
