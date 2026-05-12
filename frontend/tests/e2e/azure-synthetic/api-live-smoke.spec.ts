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
    const match = content.match(
      new RegExp(`^(?:export\\s+)?${escaped}=(.*)$`, "m"),
    );
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
    envOrSecret([
      "LIVE_SMOKE_BASE_URL",
      "NEXT_PUBLIC_API_BASE_URL",
      "BASE_URL",
    ]) ??
    // Canonical public domain — same as the AWS suite post-DNS-cutover.
    "https://vibhanshu-ai-portfolio.dev"
  ).replace(/\/+$/, "");
}

async function responseBodyExcerpt(response: {
  text: () => Promise<string>;
}): Promise<string> {
  const body = await response.text();
  return body.length > 500 ? `${body.slice(0, 500)}…` : body;
}

async function assertOk(
  response: {
    ok: () => boolean;
    status: () => number;
    text: () => Promise<string>;
  },
  label: string,
) {
  if (!response.ok()) {
    throw new Error(
      `${label} returned HTTP ${response.status()}: ${await responseBodyExcerpt(response)}`,
    );
  }
}

test.describe("Azure Synthetic: API live smoke", () => {
  test.describe.configure({ mode: "serial" });
  // Budget matches the project-level timeout in playwright.config.ts.
  test.setTimeout(120_000);

  const email = envOrSecret([
    "APP_AUTH_EMAIL",
    "TF_VAR_app_auth_email",
    "E2E_TEST_USER_EMAIL",
  ]);
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

  test.skip(
    !email || !password,
    "Set demo credentials in env or repo-root .env.secrets",
  );

  // ── 1. Health ─────────────────────────────────────────────────────────────
  test("health: GET /actuator/health responds 200 within 70s", async ({
    request,
  }) => {
    // The actuator endpoint is behind Spring Security; it is accessible on the
    // Azure custom domain without credentials (Spring's default health endpoint
    // is not protected). Allows up to 70s for a Container App scale-from-zero.
    const response = await request.get(`${baseUrl}/actuator/health`, {
      timeout: 70_000,
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.status).toBe("UP");
  });

  // ── 2. Idempotent seeding ─────────────────────────────────────────────────
  test("seeding: POST portfolio and market-data seed endpoints are idempotent", async ({
    request,
  }) => {
    const internalApiKey = envOrSecret([
      "INTERNAL_API_KEY",
      "TF_VAR_INTERNAL_API_KEY",
    ]);
    if (!internalApiKey) {
      // Seed tests are skipped in environments where the key is not available
      // (e.g. local developer machines without .env.secrets). They are mandatory
      // in CI via the INTERNAL_API_KEY job-level env.
      test.skip(true, "INTERNAL_API_KEY not set — skipping seed assertions");
    }

    const headers = { "X-Internal-Api-Key": internalApiKey! };

    // Portfolio seed: controller hardcodes the E2E user — no body needed.
    // Response: { userId, portfolioId, holdingsInserted, marketPricesUpserted }
    const portfolioSeed = await request.post(
      `${baseUrl}/api/internal/portfolio/seed`,
      { headers, timeout: 70_000 },
    );
    expect(
      portfolioSeed.status(),
      `POST /api/internal/portfolio/seed returned HTTP ${portfolioSeed.status()}`,
    ).toBe(200);
    const portfolioSeedBody = await portfolioSeed.json();
    expect(
      portfolioSeedBody.holdingsInserted,
      "portfolio seed must insert >= 160 holdings (Requirement 1.4)",
    ).toBeGreaterThanOrEqual(160);

    // Market-data seed: controller requires { userId } in the request body
    // (MarketDataSeedController.SeedRequest) — omitting it returns 400.
    // Response: { pricesUpserted }
    const marketDataSeed = await request.post(
      `${baseUrl}/api/internal/market-data/seed`,
      {
        headers,
        data: { userId: expectedUserId },
        timeout: 70_000,
      },
    );
    expect(
      marketDataSeed.status(),
      `POST /api/internal/market-data/seed returned HTTP ${marketDataSeed.status()}`,
    ).toBe(200);
    const marketDataSeedBody = await marketDataSeed.json();
    expect(
      marketDataSeedBody.pricesUpserted,
      "market-data seed must upsert >= 160 prices (Requirement 1.4)",
    ).toBeGreaterThanOrEqual(160);
  });

  // ── 3. Auth + protected endpoints ────────────────────────────────────────
  test("login POST issues token accepted by live protected GET endpoints", async ({
    request,
  }) => {
    const credentials = {
      email: email!,
      password: password!,
      expectedUserId: expectedUserId!,
    };

    // Verify unauthenticated requests are rejected
    const unauthenticated = await request.get(`${baseUrl}/api/portfolio`, {
      timeout: 70_000,
    });
    expect([401, 403]).toContain(unauthenticated.status());

    // Perform login to obtain JWT token
    const login = await request.post(`${baseUrl}/api/auth/login`, {
      data: { email: credentials.email, password: credentials.password },
      timeout: 70_000,
    });
    await assertOk(login, "POST /api/auth/login");

    const session = await login.json();
    expect(session.token).toEqual(expect.any(String));
    expect(session.userId).toBe(credentials.expectedUserId);

    // Test authenticated endpoints with the JWT token
    const headers = { Authorization: `Bearer ${session.token}` };

    // Test portfolio endpoint
    const portfolio = await request.get(`${baseUrl}/api/portfolio`, {
      headers,
      timeout: 70_000,
    });
    await assertOk(portfolio, "GET /api/portfolio");

    const portfolios = await portfolio.json();
    expect(Array.isArray(portfolios)).toBe(true);
    expect(portfolios.length).toBeGreaterThan(0);

    const demoPortfolio =
      portfolios.find(
        (item: { userId?: string }) =>
          item.userId === credentials.expectedUserId,
      ) ?? portfolios[0];
    expect(demoPortfolio.holdings?.length ?? 0).toBeGreaterThan(0);

    // Test portfolio summary endpoint — assert totalValue is positive after seeding.
    const summary = await request.get(
      `${baseUrl}/api/portfolio/summary?userId=${encodeURIComponent(credentials.expectedUserId)}`,
      { headers, timeout: 70_000 },
    );
    await assertOk(summary, "GET /api/portfolio/summary");
    const summaryData = await summary.json();
    expect(
      summaryData.totalValue,
      "portfolio summary totalValue must be > 0 after seeding",
    ).toBeGreaterThan(0);

    // Test market prices endpoint with sample tickers
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
