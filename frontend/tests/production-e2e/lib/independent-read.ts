/**
 * Wave 10.2 Step B (exit criterion 5b) - the out-of-page read.
 *
 * L8 and L9 must prove persisted state independently of the page under test, so
 * this reads GET /api/portfolio over plain HTTP with the demo token (a Node fetch
 * injected by the spec, never the page or its cache). It never throws for network
 * or parse problems: those come back as `status: 0` / `snapshot: null` so the leg
 * records a failure instead of losing the ledger line. The token is used only as
 * a request header and never appears in a result or an error.
 */
import { parsePortfolioList, type PortfolioSnapshot } from "./response-shapes";

export interface FetchInit {
  readonly method: "GET";
  readonly headers: Readonly<Record<string, string>>;
  readonly cache: "no-store";
  readonly redirect: "error";
  readonly signal: AbortSignal;
}

export interface FetchResponseLike {
  readonly status: number;
  json(): Promise<unknown>;
}

export type FetchLike = (url: string, init: FetchInit) => Promise<FetchResponseLike>;

export interface IndependentReadOptions {
  readonly fetchImpl: FetchLike;
  /** An already-validated allowlisted API origin. */
  readonly apiOrigin: string;
  readonly token: string;
  readonly userId: string;
  readonly timeoutMs: number;
}

export interface IndependentReadResult {
  /** HTTP status, or 0 when no response was obtained. */
  readonly status: number;
  /** The identity-checked portfolio when status is 200 and the body parsed; otherwise null. */
  readonly snapshot: PortfolioSnapshot | null;
}

export async function independentPortfolioRead(options: IndependentReadOptions): Promise<IndependentReadResult> {
  let response: FetchResponseLike;
  try {
    response = await options.fetchImpl(`${options.apiOrigin}/api/portfolio`, {
      method: "GET",
      headers: { Authorization: `Bearer ${options.token}`, Accept: "application/json" },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(options.timeoutMs),
    });
  } catch {
    return { status: 0, snapshot: null };
  }
  if (response.status !== 200) return { status: response.status, snapshot: null };

  try {
    const parsed = parsePortfolioList(await response.json(), options.userId);
    return { status: response.status, snapshot: parsed.ok ? parsed.snapshot : null };
  } catch {
    return { status: response.status, snapshot: null };
  }
}
