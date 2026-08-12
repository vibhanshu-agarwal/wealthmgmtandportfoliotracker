import { expect, test } from "@playwright/test";

const GATEWAY_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

test("@auth-preflight auth preflight: backend login returns JWT payload", async ({
  request,
}) => {
  const response = await request.post(`${GATEWAY_BASE_URL}/api/auth/login`, {
    data: {
      email: "dev@local",
      password: "local-dev-password-2026",
    },
  });
  expect(response.status()).toBe(200);

  const body = await response.json();
  expect(body?.token).toBeTruthy();
  expect(body?.userId).toBeTruthy();
  expect(body?.email).toBe("dev@local");
});

// Skipped: fails with a client-side "aborted" error on the proxied
// GET /api/portfolio call even though the server returns 200 (confirmed via
// Playwright's own call log and an identical curl reproduction that succeeds
// instantly). Reproduces identically locally and in CI — see
// https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/issues/87
// for the investigation. Not an auth/credentials issue (the login inside this
// test succeeds); isolated to the proxied-through-the-gateway call specifically.
test.skip("@auth-preflight auth preflight: minted JWT is accepted by API gateway", async ({
  request,
}) => {
  const jwtRes = await request.post(`${GATEWAY_BASE_URL}/api/auth/login`, {
    data: {
      email: "dev@local",
      password: "local-dev-password-2026",
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
