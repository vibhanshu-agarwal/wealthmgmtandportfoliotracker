import type { APIRequestContext } from "@playwright/test";
import { mintJwt } from "./auth";

const GATEWAY_URL = "http://localhost:8080";

/**
 * Ensures user-001 has a portfolio with AAPL and BTC holdings.
 *
 * Self-healing: if no portfolio exists, creates one via POST /api/portfolio
 * and seeds the holdings via POST /api/portfolio/{id}/holdings.
 * If a portfolio exists but has no holdings, seeds them into the existing one.
 *
 * Returns the portfolio ID.
 */
export async function ensurePortfolioWithHoldings(
  request: APIRequestContext,
  token: string,
): Promise<string> {
  // ── 1. Fetch existing portfolios ──────────────────────────────────────────
  const listRes = await request.get(`${GATEWAY_URL}/api/portfolio`, {
    headers: { Authorization: `Bearer ${token}` },
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
      headers: { Authorization: `Bearer ${token}` },
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
          Authorization: `Bearer ${token}`,
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
    headers: { Authorization: `Bearer ${token}` },
  });
  if (summaryRes.status() !== 200) {
    throw new Error(
      `GET /api/portfolio/summary returned ${summaryRes.status()} — ` +
        `ensure the portfolio-service requireUserExists fix is deployed.`,
    );
  }

  return portfolioId;
}

export { mintJwt };
