import { expect, test } from "@playwright/test";

const GATEWAY_BASE_URL = process.env.GATEWAY_BASE_URL ?? "http://127.0.0.1:8080";

test("@auth-preflight auth preflight: backend login returns JWT payload", async ({
  request,
}) => {
  const response = await request.post(`${GATEWAY_BASE_URL}/api/auth/login`, {
    data: {
      email: "dev@localhost.local",
      password: "password",
    },
  });
  expect(response.status()).toBe(200);

  const body = await response.json();
  expect(body?.token).toBeTruthy();
  expect(body?.userId).toBeTruthy();
  expect(body?.email).toBe("dev@localhost.local");
});

test("@auth-preflight auth preflight: minted JWT is accepted by API gateway", async ({
  request,
}) => {
  const jwtRes = await request.post(`${GATEWAY_BASE_URL}/api/auth/login`, {
    data: {
      email: "dev@localhost.local",
      password: "password",
    },
  });
  expect(jwtRes.status()).toBe(200);

  const jwtPayload = await jwtRes.json();
  expect(jwtPayload?.token).toBeTruthy();

  const gatewayRes = await request.get(`${GATEWAY_BASE_URL}/api/portfolio`, {
    headers: {
      Authorization: `Bearer ${jwtPayload.token}`,
    },
  });

  expect(gatewayRes.status()).toBe(200);
});
