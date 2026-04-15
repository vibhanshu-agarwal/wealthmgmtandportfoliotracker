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

test("@auth-preflight auth preflight: minted JWT is accepted by API gateway", async ({
  request,
}) => {
  const jwtRes = await request.get("/api/auth/jwt");
  expect(jwtRes.status()).toBe(200);

  const jwtPayload = await jwtRes.json();
  expect(jwtPayload?.token).toBeTruthy();

  const gatewayRes = await request.get("http://127.0.0.1:8080/api/portfolio", {
    headers: {
      Authorization: `Bearer ${jwtPayload.token}`,
    },
  });

  expect(gatewayRes.status()).toBe(200);
});
