import type { APIRequestContext } from "@playwright/test";
import { mintJwt } from "./auth";
import { e2eLoginCredentials } from "./e2e-credentials";

const GATEWAY_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const REQUIRED_TICKERS = ["AAPL", "BTC-USD"] as const;

/**
 * Resolves the E2E userId by calling API Gateway login.
 * A failed login fails the test — there is no fallback identity.
 */
async function resolveUserId(): Promise<string> {
  const credentials = e2eLoginCredentials();
  const res = await fetch(`${GATEWAY_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
  });
  if (!res.ok) {
    throw new Error(
      `[api] login failed: ${res.status} ${await res.text()} — ` +
        `cannot fall back to another identity (E2E user required).`,
    );
  }
  const data = (await res.json()) as { userId?: string };
  if (!data?.userId) {
    throw new Error(
      "[api] login succeeded but response had no userId — " +
        "cannot fall back to another identity (E2E user required).",
    );
  }
  console.log(`[api] Resolved login userId: ${data.userId}`);
  return data.userId;
}

/**
 * Asserts that the authenticated E2E user already has a Golden-State portfolio
 * containing AAPL and BTC-USD. Does not create portfolios or POST holdings.
 */
export async function ensurePortfolioWithHoldings(
  request: APIRequestContext,
  token?: string,
): Promise<string> {
  const userId = await resolveUserId();
  const bearerToken = token ?? mintJwt(userId);

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
  if (!Array.isArray(portfolios) || portfolios.length === 0) {
    throw new Error(
      "[api] Golden-State seeding was skipped or failed: GET /api/portfolio " +
        `returned no portfolios for the E2E user (expected at least one with ` +
        `tickers [${REQUIRED_TICKERS.join(", ")}]; found none). ` +
        "This helper no longer creates portfolios.",
    );
  }

  const portfolio = portfolios[0] as {
    id: string;
    holdings?: Array<{ assetTicker: string }>;
  };
  const portfolioId = String(portfolio.id);
  const presentTickers = new Set(
    (portfolio.holdings ?? []).map((h) => h.assetTicker),
  );
  const missing = REQUIRED_TICKERS.filter((ticker) => !presentTickers.has(ticker));
  if (missing.length > 0) {
    const found = [...presentTickers].join(", ") || "(none)";
    throw new Error(
      "[api] Golden-State seeding was skipped or incomplete: expected tickers " +
        `[${REQUIRED_TICKERS.join(", ")}] on portfolio ${portfolioId}; ` +
        `found [${found}]; missing [${missing.join(", ")}]. ` +
        "This helper no longer POSTs holdings.",
    );
  }

  console.log(`[api] Using existing Golden-State portfolio id=${portfolioId}`);
  return portfolioId;
}

export { mintJwt };
