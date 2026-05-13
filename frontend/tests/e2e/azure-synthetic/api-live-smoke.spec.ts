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
  test("health: GET /actuator/health responds 200 during scale-from-zero", async ({
    request,
  }) => {
    // Azure Container Apps run with minReplicas=0 (Free-tier cost optimisation).
    // A request against a scaled-to-zero replica receives HTTP 503 from the ACA
    // ingress until the new replica passes its readiness probe. That 503 is a
    // normal response (not a request timeout), so a single-shot call with a
    // large `timeout` will fail fast instead of waiting. We therefore poll the
    // endpoint until it returns 200 or the budget is exhausted.
    //
    // Budget sized for a worst-case Spring Boot 4 cold start on the Consumption
    // plan: image pull + JVM start + Flyway validate + Kafka admin client +
    // first Lettuce TLS handshake against Upstash can add up to ~90s. 180s
    // gives headroom for retries without masking a genuinely broken deploy.
    //
    // Subsequent tests in this suite run serially (see mode: "serial" above),
    // so once health returns 200 the container stays warm for the seed + login
    // tests and they can continue using single-shot requests with 70s timeouts.
    test.setTimeout(200_000);

    const DEADLINE_MS = 180_000;
    const POLL_INTERVAL_MS = 5_000;
    const start = Date.now();

    let lastStatus = 0;
    let lastError = "";

    while (Date.now() - start < DEADLINE_MS) {
      const remaining = DEADLINE_MS - (Date.now() - start);
      try {
        const response = await request.get(`${baseUrl}/actuator/health`, {
          timeout: Math.min(20_000, remaining),
        });
        lastStatus = response.status();
        lastError = "";

        if (lastStatus === 200) {
          const body = await response.json();
          expect(body.status).toBe("UP");
          return;
        }

        // 5xx from ACA ingress during cold start is expected. Anything else
        // (404, 401) is a real problem — fail fast so we don't burn the full
        // budget on a misconfigured environment.
        if (lastStatus < 500 || lastStatus >= 600) {
          const body = await responseBodyExcerpt(response);
          throw new Error(
            `GET /actuator/health returned unexpected HTTP ${lastStatus}: ${body}`,
          );
        }
      } catch (err: unknown) {
        // Playwright throws TimeoutError when the request exceeds its timeout.
        // During ACA scale-from-zero the TCP connection may hang (image pull,
        // JVM startup) — treat the same as a 503 and keep polling.
        const isTimeout =
          err instanceof Error && /[Tt]imeout/.test(err.message);
        if (!isTimeout) throw err;
        lastError = err.message;
      }

      const sleep = Math.min(
        POLL_INTERVAL_MS,
        DEADLINE_MS - (Date.now() - start),
      );
      if (sleep <= 0) break;
      await new Promise((resolve) => setTimeout(resolve, sleep));
    }

    const detail = lastError
      ? `last error: ${lastError}`
      : `last HTTP status: ${lastStatus}`;
    throw new Error(
      `GET /actuator/health did not return 200 within ${Math.round((Date.now() - start) / 1000)}s (${detail})`,
    );
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
