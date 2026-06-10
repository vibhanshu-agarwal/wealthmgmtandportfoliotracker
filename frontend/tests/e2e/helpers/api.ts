import type { APIRequestContext } from "@playwright/test";
import { mintJwt } from "./auth";

const GATEWAY_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/**
 * Resolves the active userId by calling API Gateway login.
 *
 * Falls back to "user-001" (Flyway seed) if login is unavailable.
 */
async function resolveUserId(request: APIRequestContext): Promise<string> {
  try {
    const res = await request.post(`${GATEWAY_URL}/api/auth/login`, {
      data: {
        email: "dev@localhost.local",
        password: "password",
      },
    });
    if (res.ok()) {
      const data = await res.json();
      if (data?.userId) {
        console.log(`[api] Resolved login userId: ${data.userId}`);
        return data.userId;
      }
    }
  } catch {
    // Gateway login unavailable — fall back to Flyway seed userId
  }
  console.log("[api] Could not resolve login userId — falling back to user-001");
  return "user-001";
}

/**
 * Ensures the authenticated user has a portfolio with AAPL and BTC holdings.
 *
 * Dynamically resolves the login userId from API Gateway, then
 * mints an HS256 JWT with that userId as the sub claim. This ensures the
 * API Gateway routes the request to the correct user's portfolio.
 *
 * Self-healing: creates a portfolio and seeds holdings if none exist.
 */
export async function ensurePortfolioWithHoldings(
  request: APIRequestContext,
  token?: string,
): Promise<string> {
  // Resolve the active userId and mint a matching JWT
  const userId = await resolveUserId(request);
  const bearerToken = token ?? mintJwt(userId);

  // ── 1. Fetch existing portfolios ──────────────────────────────────────────
  const listRes = await request.get(`${GATEWAY_URL}/api/portfolio`, {
    headers: { Authorization: `Bearer ${bearerToken}` },
  });

  if (listRes.status() !== 200) {
    throw new Error(
      `GET /api/portfolio returned ${listRes.status()} — ` +
        `ensure the API Gateway and portfolio-service are running.`,
    );
  }

  const portfolios = await listRes.json();
  let portfolioId: string;

  // ── 2. Create portfolio if none exists ────────────────────────────────────
  if (!Array.isArray(portfolios) || portfolios.length === 0) {
    console.log("[api] No portfolio found — creating one via POST /api/portfolio");
    const createRes = await request.post(`${GATEWAY_URL}/api/portfolio`, {
      headers: { Authorization: `Bearer ${bearerToken}` },
    });
    if (createRes.status() !== 201) {
      throw new Error(
        `POST /api/portfolio returned ${createRes.status()} — ` +
          `expected 201. Body: ${await createRes.text()}`,
      );
    }
    const created = await createRes.json();
    portfolioId = String(created.id);
    console.log(`[api] Created portfolio id=${portfolioId}`);
  } else {
    portfolioId = String(portfolios[0].id);
    console.log(`[api] Using existing portfolio id=${portfolioId}`);
  }

  // ── 3. Seed AAPL and BTC if not already present ───────────────────────────
  const holdingsNeeded = [
    { ticker: "AAPL", quantity: 12 },
    { ticker: "BTC", quantity: 0.75 },
  ];

  const existingTickers: string[] = (portfolios[0]?.holdings ?? []).map(
    (h: { assetTicker: string }) => h.assetTicker,
  );

  for (const { ticker, quantity } of holdingsNeeded) {
    if (existingTickers.includes(ticker)) {
      console.log(`[api] Holding ${ticker} already present — skipping`);
      continue;
    }
    console.log(`[api] Adding holding ${ticker} qty=${quantity}`);
    const holdingRes = await request.post(
      `${GATEWAY_URL}/api/portfolio/${portfolioId}/holdings`,
      {
        headers: {
          Authorization: `Bearer ${bearerToken}`,
          "Content-Type": "application/json",
        },
        data: { ticker, quantity },
      },
    );
    if (holdingRes.status() !== 201) {
      throw new Error(
        `POST /api/portfolio/${portfolioId}/holdings returned ${holdingRes.status()} ` +
          `for ticker=${ticker}. Body: ${await holdingRes.text()}`,
      );
    }
  }

  // ── 4. Verify summary endpoint is reachable ───────────────────────────────
  const summaryRes = await request.get(`${GATEWAY_URL}/api/portfolio/summary`, {
    headers: { Authorization: `Bearer ${bearerToken}` },
  });
  if (summaryRes.status() !== 200) {
    throw new Error(
      `GET /api/portfolio/summary returned ${summaryRes.status()} — ` +
        `ensure the portfolio-service requireUserExists fix is deployed.`,
    );
  }

  // ── 5. Poll until holdings are visible in GET /api/portfolio ──────────────
  // The backend processes holdings synchronously, but under load or after a
  // cold start the read-your-own-write may lag slightly. Poll for up to 10s
  // so the browser never hits an empty holdings list immediately after seeding.
  const requiredTickers = holdingsNeeded.map((h) => h.ticker);
  const holdingsVisible = await pollUntilHoldingsVisible(
    request,
    bearerToken,
    portfolioId,
    requiredTickers,
    10_000,
  );
  if (!holdingsVisible) {
    console.warn(
      `[api] Holdings not yet visible in GET /api/portfolio after 10s — proceeding anyway`,
    );
  }

  return portfolioId;
}

/**
 * Polls GET /api/portfolio until all required tickers appear in the holdings list,
 * or until the timeout expires. Returns true when all tickers are found.
 */
async function pollUntilHoldingsVisible(
  request: APIRequestContext,
  bearerToken: string,
  portfolioId: string,
  requiredTickers: string[],
  timeoutMs: number,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  const pollInterval = 1_000;

  while (Date.now() < deadline) {
    try {
      const res = await request.get(`${GATEWAY_URL}/api/portfolio`, {
        headers: { Authorization: `Bearer ${bearerToken}` },
      });
      if (res.ok()) {
        const portfolios = await res.json();
        const portfolio = Array.isArray(portfolios)
          ? portfolios.find((p: { id: string }) => p.id === portfolioId)
          : null;
        const holdings: Array<{ assetTicker: string }> = portfolio?.holdings ?? [];
        const presentTickers = new Set(holdings.map((h) => h.assetTicker));
        const allPresent = requiredTickers.every((t) => presentTickers.has(t));
        if (allPresent) {
          console.log(`[api] All required tickers visible in GET /api/portfolio`);
          return true;
        }
        console.log(
          `[api] Holdings not yet complete — found: [${[...presentTickers].join(",")}], ` +
            `need: [${requiredTickers.join(",")}] — retrying...`,
        );
      }
    } catch {
      // transient error — keep polling
    }
    await new Promise((r) => setTimeout(r, pollInterval));
  }
  return false;
}

export { mintJwt };
