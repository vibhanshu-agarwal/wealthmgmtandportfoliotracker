import fs from "node:fs";
import path from "node:path";
import { expect, test } from "@playwright/test";

const SEEDED_DEMO_USER_ID = "00000000-0000-0000-0000-000000000e2e";

function secretFromEnvFile(keys: string[]): string | undefined {
  const candidates = [
    path.resolve(process.cwd(), "../.env.secrets"),
    path.resolve(process.cwd(), ".env.secrets"),
  ];
  const envPath = candidates.find((candidate) => fs.existsSync(candidate));
  if (!envPath) return undefined;

  const content = fs.readFileSync(envPath, "utf-8");
  for (const key of keys) {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const match = content.match(new RegExp(`^(?:export\\s+)?${escaped}=(.*)$`, "m"));
    const value = match?.[1]?.trim().replace(/^['\"]|['\"]$/g, "");
    if (value) return value;
  }
  return undefined;
}

function envOrSecret(keys: string[], fallback?: string): string | undefined {
  for (const key of keys) {
    const value = process.env[key]?.trim();
    if (value) return value;
  }
  return secretFromEnvFile(keys) ?? fallback;
}

function liveBaseUrl(): string {
  return (
    envOrSecret(["LIVE_SMOKE_BASE_URL", "NEXT_PUBLIC_API_BASE_URL", "BASE_URL"]) ??
    "http://vibhanshu-ai-portfolio.dev"
  ).replace(/\/+$/, "");
}

async function responseBodyExcerpt(response: { text: () => Promise<string> }): Promise<string> {
  const body = await response.text();
  return body.length > 500 ? `${body.slice(0, 500)}…` : body;
}

async function assertOk(response: { ok: () => boolean; status: () => number; text: () => Promise<string> }, label: string) {
  if (!response.ok()) {
    throw new Error(`${label} returned HTTP ${response.status()}: ${await responseBodyExcerpt(response)}`);
  }
}

test.describe("AWS Synthetic: API live smoke", () => {
  test.describe.configure({ mode: "serial" });
  test.setTimeout(120_000);

  const email = envOrSecret(["APP_AUTH_EMAIL", "TF_VAR_app_auth_email", "E2E_TEST_USER_EMAIL"]);
  const password = envOrSecret([
    "APP_AUTH_PASSWORD",
    "TF_VAR_app_auth_password",
    "E2E_TEST_USER_PASSWORD",
  ]);
  const expectedUserId = envOrSecret(
    ["APP_AUTH_USER_ID", "TF_VAR_app_auth_user_id", "E2E_TEST_USER_ID"],
    SEEDED_DEMO_USER_ID,
  );
  const baseUrl = liveBaseUrl();

  test.skip(!email || !password, "Set demo credentials in env or repo-root .env.secrets");

  test("login POST issues token accepted by live protected GET endpoints", async ({ request }) => {
    const credentials = { email: email!, password: password!, expectedUserId: expectedUserId! };
    const unauthenticated = await request.get(`${baseUrl}/api/portfolio`, {
      timeout: 70_000,
    });
    expect([401, 403]).toContain(unauthenticated.status());

    const login = await request.post(`${baseUrl}/api/auth/login`, {
      data: { email: credentials.email, password: credentials.password },
      timeout: 70_000,
    });
    await assertOk(login, "POST /api/auth/login");

    const session = await login.json();
    expect(session.token).toEqual(expect.any(String));
    expect(session.userId).toBe(credentials.expectedUserId);

    const headers = { Authorization: `Bearer ${session.token}` };
    const portfolio = await request.get(`${baseUrl}/api/portfolio`, {
      headers,
      timeout: 70_000,
    });
    await assertOk(portfolio, "GET /api/portfolio");

    const portfolios = await portfolio.json();
    expect(Array.isArray(portfolios)).toBe(true);
    expect(portfolios.length).toBeGreaterThan(0);

    const demoPortfolio = portfolios.find(
      (item: { userId?: string }) => item.userId === credentials.expectedUserId,
    ) ?? portfolios[0];
    expect(demoPortfolio.holdings?.length ?? 0).toBeGreaterThan(0);

    const summary = await request.get(
      `${baseUrl}/api/portfolio/summary?userId=${encodeURIComponent(credentials.expectedUserId)}`,
      { headers, timeout: 70_000 },
    );
    await assertOk(summary, "GET /api/portfolio/summary");

    const tickers = demoPortfolio.holdings
      .slice(0, 3)
      .map((holding: { assetTicker: string }) => holding.assetTicker)
      .join(",");
    const prices = await request.get(
      `${baseUrl}/api/market/prices?tickers=${encodeURIComponent(tickers)}`,
      { headers, timeout: 70_000 },
    );
    await assertOk(prices, "GET /api/market/prices");
  });
});