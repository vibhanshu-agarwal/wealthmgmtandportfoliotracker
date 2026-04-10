import type { APIRequestContext } from "@playwright/test";
import { mintJwt } from "./auth";

const GATEWAY_URL = "http://localhost:8080";

/**
 * Verifies that the portfolio for user-001 exists and has holdings (AAPL and BTC).
 * The data is seeded by Flyway migration V3 — this function just confirms it's accessible
 * via the live API Gateway.
 *
 * Also verifies the /api/portfolio/summary endpoint returns a non-zero totalValue,
 * confirming the requireUserExists fix (user-001 is a non-UUID sub claim) is in effect.
 *
 * Returns the portfolio ID of the first portfolio found.
 */
export async function ensurePortfolioWithHoldings(
  request: APIRequestContext,
  token: string
): Promise<string> {
  const response = await request.get(`${GATEWAY_URL}/api/portfolio`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (response.status() !== 200) {
    throw new Error(
      `GET /api/portfolio returned ${response.status()} — ` +
        `expected 200. Ensure the API Gateway is running and the Flyway V3 seed migration has run.`
    );
  }

  const portfolios = await response.json();

  if (!Array.isArray(portfolios) || portfolios.length === 0) {
    throw new Error(
      `GET /api/portfolio returned an empty array — ` +
        `expected at least one portfolio. Ensure the Flyway V3 seed migration has run for user-001.`
    );
  }

  const first = portfolios[0];

  if (!Array.isArray(first.holdings) || first.holdings.length === 0) {
    throw new Error(
      `The first portfolio (id=${first.id}) has no holdings — ` +
        `expected AAPL, TSLA, and BTC from the Flyway V3 seed migration. ` +
        `This may indicate the Portfolio.getHoldings() fix (task 1.2) did not take effect.`
    );
  }

  // Also verify the summary endpoint is reachable (confirms requireUserExists fix for non-UUID sub claims)
  const summaryResponse = await request.get(`${GATEWAY_URL}/api/portfolio/summary`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (summaryResponse.status() !== 200) {
    throw new Error(
      `GET /api/portfolio/summary returned ${summaryResponse.status()} — ` +
        `expected 200. This likely means requireUserExists is still rejecting the "user-001" sub claim as a non-UUID. ` +
        `Ensure the PortfolioService.requireUserExists fix is deployed.`
    );
  }

  const summary = await summaryResponse.json();
  if (!summary.totalValue || Number(summary.totalValue) === 0) {
    throw new Error(
      `GET /api/portfolio/summary returned totalValue=0 — ` +
        `expected a non-zero value. Ensure market_prices are seeded (Flyway V2) and the portfolio-service is running.`
    );
  }

  return String(first.id);
}

export { mintJwt };
