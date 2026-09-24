import { apiPath } from "@/lib/config/api";

/**
 * Response of `GET /api/portfolio/fx-rates` (rehearsal defect #4): each requested quote
 * currency's rate into the base currency, from portfolio-service's shared FxRateProvider —
 * the same rates the holdings valuation uses. A null rate means the conversion is
 * unavailable; it is never replaced with 1.
 */
export interface FxRatesResponse {
  baseCurrency: string;
  rates: Record<string, number | null>;
}

/**
 * Plain `fetch`, like `loadMarketPrices`: a failure here only makes display-only estimates
 * unavailable, so it must not clear the session or navigate away as `fetchWithAuthClient`
 * does on a 401.
 */
export async function fetchFxRates(currencies: string[], token: string): Promise<FxRatesResponse> {
  const params = new URLSearchParams({ currencies: currencies.join(",") });
  const response = await fetch(`${apiPath("/portfolio/fx-rates")}?${params.toString()}`, {
    method: "GET",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error(`fx-rates failed (${response.status})`);
  }
  return (await response.json()) as FxRatesResponse;
}
