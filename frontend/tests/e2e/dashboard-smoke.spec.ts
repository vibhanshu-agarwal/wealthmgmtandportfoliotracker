import { expect, test } from "@playwright/test";

test("static export server responds on login route", async ({ request }) => {
  const response = await request.get("/login");
  expect(response.status()).toBe(200);

  const html = await response.text();
  expect(html).toContain("<!DOCTYPE html>");
});
