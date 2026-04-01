import { expect, test } from "@playwright/test";

test("standalone build starts and renders dashboard overview", async ({ page }) => {
  await page.goto("/overview");

  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
  await expect(page.getByText("Dashboard overview coming soon.")).toBeVisible();
});
