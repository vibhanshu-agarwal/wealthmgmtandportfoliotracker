/**
 * Node-side API client for setup, independent readback and isolation probes.
 *
 * It uses Node's fetch, not Playwright's request fixture, so certification-account
 * passwords sent to /api/auth/login never enter a Playwright trace. Every auth request
 * goes through the pacer. Nothing here retries: a 429 or 5xx surfaces as a failure.
 */
import type { Credentials } from "./config";
import type { Holding } from "./values";

export interface AuthSession {
  readonly token: string;
  readonly userId: string;
  readonly email: string;
  readonly name: string;
}

export interface PortfolioReadback {
  readonly userId: string;
  readonly version: number;
  readonly holdings: Holding[];
}

export interface AssetPriceFreshness {
  readonly state: "FRESH" | "STALE" | "UNKNOWN" | "MISSING";
  readonly oldestKnownAssetPriceObservationTimestamp?: string;
  readonly staleHoldings: number;
  readonly unknownPriceHoldings: number;
  readonly missingPriceHoldings: number;
}

export interface SummaryReadback {
  readonly totalValue: number;
  readonly totalHoldings: number;
  readonly partialValuation: boolean;
  readonly assetPriceFreshness: AssetPriceFreshness;
}

export interface AnalyticsHolding {
  readonly ticker: string;
  /** Per-unit price in the quote currency; null when the ticker has no price (finding F1). */
  readonly currentPrice: number | null;
  /** Null when the price or FX rate is unavailable (finding F1). */
  readonly currentValueBase: number | null;
  readonly displayAssetClass?: string | null;
  readonly change24hPercent: number | null;
  /** Per-unit, quote-currency price change (finding F13). */
  readonly change24hAbsolute: number | null;
  /** D11: the position's 24h change in base currency; absent from an older backend. */
  readonly change24hValueBase?: number | null;
}

export interface AnalyticsReadback {
  readonly totalValue: number;
  readonly holdings: AnalyticsHolding[];
  readonly partialValuation: boolean;
  /** D11: the portfolio's 24h change in base currency; absent from an older backend. */
  readonly totalChange24hBase?: number | null;
  /** D11: which holdings the 24h totals cover; absent from an older backend. */
  readonly change24hCoverage?: {
    readonly holdingsWithChange: number;
    readonly countedHoldings: number;
    readonly totalHoldings: number;
    readonly partial: boolean;
  } | null;
  readonly performanceCoverage?: { partial?: boolean; holdingsWithHistory?: number; totalHoldings?: number } | null;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

export class ApiClient {
  constructor(
    private readonly apiOrigin: string,
    private readonly paceAuth: () => Promise<void>,
  ) {}

  private async request(method: string, path: string, init: { token?: string; body?: unknown; headers?: Record<string, string> } = {}) {
    const response = await fetch(`${this.apiOrigin}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}),
        ...init.headers,
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
    let json: unknown = null;
    try {
      json = await response.json();
    } catch {
      json = null;
    }
    return { status: response.status, json };
  }

  /** Logs in; returns null on 401 so callers can decide whether provisioning is allowed. */
  async login(credentials: Credentials): Promise<AuthSession | null> {
    await this.paceAuth();
    const { status, json } = await this.request("POST", "/api/auth/login", {
      body: { email: credentials.email, password: credentials.password },
    });
    if (status === 401) return null;
    if (status !== 200) throw new ApiError(`login returned HTTP ${status}`, status);
    return toSession(json);
  }

  async signup(credentials: Credentials, name: string): Promise<AuthSession> {
    await this.paceAuth();
    const { status, json } = await this.request("POST", "/api/auth/signup", {
      body: { email: credentials.email, password: credentials.password, name },
    });
    if (status !== 201) throw new ApiError(`signup returned HTTP ${status}`, status);
    return toSession(json);
  }

  async portfolio(session: AuthSession, extraHeaders?: Record<string, string>): Promise<PortfolioReadback> {
    const { status, json } = await this.request("GET", "/api/portfolio", { token: session.token, headers: extraHeaders });
    if (status !== 200 || !Array.isArray(json)) throw new ApiError(`portfolio read returned HTTP ${status}`, status);
    if (json.length !== 1) throw new Error(`expected exactly one portfolio, got ${json.length}`);
    const row = json[0] as { userId?: unknown; version?: unknown; holdings?: unknown };
    if (typeof row.userId !== "string" || typeof row.version !== "number" || !Array.isArray(row.holdings)) {
      throw new Error("portfolio read has an unexpected shape");
    }
    const holdings = row.holdings.map((h) => {
      const record = h as { assetTicker?: unknown; quantity?: unknown };
      if (typeof record.assetTicker !== "string" || typeof record.quantity !== "string") {
        throw new Error("holding has an unexpected shape");
      }
      return { ticker: record.assetTicker, quantity: record.quantity };
    });
    return { userId: row.userId, version: row.version, holdings };
  }

  async summary(session: AuthSession): Promise<SummaryReadback> {
    const { status, json } = await this.request("GET", "/api/portfolio/summary", { token: session.token });
    if (status !== 200) throw new ApiError(`summary read returned HTTP ${status}`, status);
    return json as SummaryReadback;
  }

  async analytics(session: AuthSession): Promise<AnalyticsReadback> {
    const { status, json } = await this.request("GET", "/api/portfolio/analytics", { token: session.token });
    if (status !== 200) throw new ApiError(`analytics read returned HTTP ${status}`, status);
    return json as AnalyticsReadback;
  }

  async putHoldings(session: AuthSession, expectedVersion: number, holdings: readonly Holding[]) {
    return this.request("PUT", "/api/portfolio/holdings", {
      token: session.token,
      body: { expectedVersion, holdings: holdings.map((h) => ({ ticker: h.ticker, quantity: h.quantity })) },
    });
  }
}

function toSession(json: unknown): AuthSession {
  const body = json as Partial<AuthSession> | null;
  if (!body || typeof body.token !== "string" || typeof body.userId !== "string" || typeof body.email !== "string") {
    throw new Error("auth response has an unexpected shape");
  }
  return { token: body.token, userId: body.userId, email: body.email, name: typeof body.name === "string" ? body.name : "User" };
}
