import { expect, test } from "@playwright/test";

test("@auth-preflight auth preflight: jwt health self-check succeeds", async ({
  request,
}) => {
  const response = await request.get("/api/auth/jwt/health");
  expect(response.status()).toBe(200);

  const body = await response.json();
  expect(body).toMatchObject({
    status: "ok",
    checks: {
      sessionLookup: true,
      tokenMint: true,
      tokenVerify: true,
    },
  });
});
