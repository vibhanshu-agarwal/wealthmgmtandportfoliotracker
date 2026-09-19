/**
 * Wave 10.2 Step B (exit criterion 5b) - the page's own network observations.
 *
 * The recorder is fed by the page's `request`, `response`, `requestfailed`,
 * `console` and `framenavigated` events (armed before the first navigation, so a
 * listener always exists before the action that triggers a request). No route is
 * intercepted. It is written against small structural interfaces that Playwright's
 * Request/Response/ConsoleMessage satisfy, so it can be exercised with fakes.
 *
 * Only requests to the allowlisted API origin are stored. Bodies are parsed into
 * booleans, counts and in-memory snapshots; raw bodies, headers, page text and
 * console text are never retained (the only request text kept is the JSON body of
 * the two PUT requests, which the legs compare and then discard with the object).
 */
import type { HttpEntry } from "./ledger";
import { classifyRequest, type Origins } from "./requests";
import { tickersFromPricesUrl, type PriceObservation } from "./price-attribution";
import { pollUntil, type PollOptions } from "./wait";
import {
  parseCatalog,
  parsePortfolioList,
  parsePortfolioObject,
  parsePresence,
  parsePriceRows,
  parseSummaryFreshness,
  type CatalogShape,
  type PortfolioParseResult,
  type PortfolioSnapshot,
  type SummaryFreshness,
} from "./response-shapes";

export interface RequestLike {
  url(): string;
  method(): string;
  headers(): Record<string, string>;
  postData(): string | null;
  allHeaders(): Promise<Record<string, string>>;
}

export interface ResponseLike {
  status(): number;
  headers(): Record<string, string>;
  request(): RequestLike;
  json(): Promise<unknown>;
}

export interface ConsoleMessageLike {
  type(): string;
  text(): string;
}

export type ParsedBody =
  | { readonly type: "portfolio"; readonly result: PortfolioParseResult }
  | { readonly type: "putResult"; readonly result: PortfolioParseResult }
  | { readonly type: "summary"; readonly freshness: SummaryFreshness }
  | { readonly type: "catalog"; readonly catalog: CatalogShape }
  | { readonly type: "presence"; readonly anotherSessionActive: boolean | null }
  | { readonly type: "prices"; readonly arrayShaped: boolean; readonly nonNullPriceSeen: boolean }
  | { readonly type: "unparseable" };

export interface RecordedRequest {
  /** Monotonic emission order; `mark()` values are comparable with it. */
  readonly id: number;
  readonly request: RequestLike;
  /** Upper-cased. */
  readonly method: string;
  /** Contract path template, or null for an API path outside the contract's list. */
  readonly path: string | null;
  readonly hasIfNoneMatch: boolean;
  readonly hasAuthorization: boolean;
  /** Ordered tickers of a market-price request (memory only, never recorded). */
  readonly tickers: readonly string[];
  /** JSON text of a PUT holdings / demo-reset body; null for everything else. */
  readonly requestBodyText: string | null;
  status: number | null;
  respondedTick: number | null;
  etagPresent: boolean;
  parsed: ParsedBody | null;
  failed: boolean;
  failedTick: number | null;
  /** True once the response body was read, or the request failed: nothing more will change. */
  settled: boolean;
  /** Injected-clock time at which the request settled; null until it did. Used only for pacing. */
  settledAtMs: number | null;
}

export interface PageRecorderOptions {
  readonly origins: Origins;
  readonly ledgerPaths: readonly string[];
  readonly userId: string;
  /** The clock that stamps `settledAtMs`; the spec passes the same clock it waits with. Defaults to wall time. */
  readonly now?: () => number;
}

/**
 * The status the ledger records for a request: 0 when it failed or no response has arrived. Every count or
 * status fact a leg reports and every `http` entry it records go through this one mapping, so the two can
 * never disagree about the same request (the contract's `passHttp` table checks exactly that).
 */
export function ledgerStatusOf(entry: RecordedRequest | undefined): number {
  if (entry === undefined || entry.failed) return 0;
  return entry.status ?? 0;
}

/** The ledger view of recorded requests: method, path template, status. Never a URL or a body. */
export function httpOf(entries: readonly RecordedRequest[]): HttpEntry[] {
  const http: HttpEntry[] = [];
  for (const entry of entries) {
    if (entry.path === null || (entry.method !== "GET" && entry.method !== "PUT")) continue;
    http.push({ method: entry.method, path: entry.path, status: ledgerStatusOf(entry) });
  }
  return http;
}

/**
 * Pacing before a write (spec 4.1): the page refetches the portfolio and its prices on a 60 s timer, and the
 * gateway's per-user rate limit counts requests per second, so a write must not share a second with such a
 * burst. A write waits until every page-side portfolio and price read has settled and this long has passed
 * since the most recent one did.
 */
export const PAGE_READ_QUIET_MS = 2_000;

export type PageReadQuietState =
  | { readonly state: "in_flight" }
  | { readonly state: "settling"; readonly quietInMs: number }
  | { readonly state: "quiet" };

/**
 * Pure: is it safe to send a write now? `in_flight` while any listed read has not settled, `settling` until
 * `quietMs` has passed since the latest settle (and how much longer), `quiet` after that. An empty list is
 * quiet. A settled read with no settle time counts as in flight, so missing information never reads as quiet.
 */
export function pageReadQuietState(
  reads: readonly Pick<RecordedRequest, "settled" | "settledAtMs">[],
  nowMs: number,
  quietMs: number = PAGE_READ_QUIET_MS,
): PageReadQuietState {
  let latestSettledAtMs: number | null = null;
  for (const read of reads) {
    if (!read.settled || read.settledAtMs === null) return { state: "in_flight" };
    if (latestSettledAtMs === null || read.settledAtMs > latestSettledAtMs) latestSettledAtMs = read.settledAtMs;
  }
  if (latestSettledAtMs === null) return { state: "quiet" };
  const quietForMs = nowMs - latestSettledAtMs;
  if (quietForMs >= quietMs) return { state: "quiet" };
  return { state: "settling", quietInMs: quietMs - Math.max(quietForMs, 0) };
}

/** Polls (bounded by `options.timeoutMs`, on `options.clock`) until the page's own reads are quiet. False on the deadline. */
export function waitForQuietPageReads(
  recorder: PageRecorder,
  options: PollOptions,
  quietMs: number = PAGE_READ_QUIET_MS,
): Promise<boolean> {
  return pollUntil(
    () => pageReadQuietState(recorder.pageReads(), options.clock.now(), quietMs).state === "quiet",
    options,
  );
}

const PUT_BODY_PATHS = new Set(["/api/portfolio/holdings", "/api/portfolio/demo-reset"]);
/** The page-side reads whose burst the pacing waits out (spec 4.1). */
const PACED_READ_PATHS = new Set(["/api/portfolio", "/api/market/prices"]);
const CORS_CONSOLE_PATTERN = /cors|access-control-allow/i;

function hasHeader(headers: Record<string, string>, lowerCaseName: string): boolean {
  return Object.keys(headers).some((name) => name.toLowerCase() === lowerCaseName);
}

export class PageRecorder {
  private readonly origins: Origins;
  private readonly ledgerPaths: readonly string[];
  private readonly userId: string;
  private readonly now: () => number;
  private tick = 0;
  private readonly recorded: RecordedRequest[] = [];
  private readonly byRequest = new Map<RequestLike, RecordedRequest>();
  private readonly corsErrorTicks: number[] = [];
  private loginNavigationSeen = false;
  private foreignAuthorizedCount = 0;

  constructor(options: PageRecorderOptions) {
    this.origins = options.origins;
    this.ledgerPaths = options.ledgerPaths;
    this.userId = options.userId;
    this.now = options.now ?? (() => Date.now());
  }

  // -- event intake ------------------------------------------------------------

  onRequest(request: RequestLike): void {
    const classified = classifyRequest(request.method(), request.url(), this.origins, this.ledgerPaths);
    const headers = request.headers();
    const hasAuthorization = hasHeader(headers, "authorization");

    if (classified.kind === "other") {
      // The bearer token must only ever go to the allowlisted API origin.
      if (hasAuthorization) this.foreignAuthorizedCount += 1;
      return;
    }
    if (classified.kind !== "api") return;

    this.tick += 1;
    const entry: RecordedRequest = {
      id: this.tick,
      request,
      method: classified.method,
      path: classified.path,
      hasIfNoneMatch: hasHeader(headers, "if-none-match"),
      hasAuthorization,
      tickers: classified.path === "/api/market/prices" ? tickersFromPricesUrl(request.url()) : [],
      requestBodyText:
        classified.method === "PUT" && classified.path !== null && PUT_BODY_PATHS.has(classified.path)
          ? request.postData()
          : null,
      status: null,
      respondedTick: null,
      etagPresent: false,
      parsed: null,
      failed: false,
      failedTick: null,
      settled: false,
      settledAtMs: null,
    };
    this.recorded.push(entry);
    this.byRequest.set(request, entry);
  }

  /** Never rejects: a body that cannot be read or parsed is recorded as "unparseable". */
  async onResponse(response: ResponseLike): Promise<void> {
    const entry = this.byRequest.get(response.request());
    if (entry === undefined) return;

    this.tick += 1;
    entry.respondedTick = this.tick;
    entry.status = response.status();
    entry.etagPresent = hasHeader(response.headers(), "etag");

    if (entry.status === 200) {
      try {
        entry.parsed = await this.parseBody(entry, response);
      } catch {
        entry.parsed = { type: "unparseable" };
      }
    }
    entry.settledAtMs = this.now();
    entry.settled = true;
  }

  onRequestFailed(request: RequestLike): void {
    const entry = this.byRequest.get(request);
    if (entry === undefined) return;
    this.tick += 1;
    entry.failed = true;
    entry.failedTick = this.tick;
    entry.settledAtMs = this.now();
    entry.settled = true;
  }

  /** Console text is inspected for a CORS signature and discarded immediately. */
  onConsole(message: ConsoleMessageLike): void {
    if (message.type() === "error" && CORS_CONSOLE_PATTERN.test(message.text())) {
      this.tick += 1;
      this.corsErrorTicks.push(this.tick);
    }
  }

  onMainFrameNavigated(url: string): void {
    const classified = classifyRequest("GET", url, this.origins, []);
    if (classified.kind === "frontend" && new URL(url).pathname.startsWith("/login")) {
      this.loginNavigationSeen = true;
    }
  }

  private async parseBody(entry: RecordedRequest, response: ResponseLike): Promise<ParsedBody | null> {
    if (entry.path === null) return null;
    const key = `${entry.method} ${entry.path}`;
    switch (key) {
      case "GET /api/portfolio":
        return { type: "portfolio", result: parsePortfolioList(await response.json(), this.userId) };
      case "PUT /api/portfolio/holdings":
      case "PUT /api/portfolio/demo-reset":
        return { type: "putResult", result: parsePortfolioObject(await response.json(), this.userId) };
      case "GET /api/portfolio/summary":
        return { type: "summary", freshness: parseSummaryFreshness(await response.json()) };
      case "GET /api/assets":
        return { type: "catalog", catalog: parseCatalog(await response.json()) };
      case "GET /api/presence/demo":
        return { type: "presence", anotherSessionActive: parsePresence(await response.json()) };
      case "GET /api/market/prices": {
        const rows = parsePriceRows(await response.json());
        return { type: "prices", arrayShaped: rows.arrayShaped, nonNullPriceSeen: rows.nonNullPriceSeen };
      }
      default:
        return null;
    }
  }

  // -- queries -----------------------------------------------------------------

  /** A position in the event stream; entries with a greater `id` were emitted after it. */
  mark(): number {
    return this.tick;
  }

  entriesAfter(mark: number, predicate: (entry: RecordedRequest) => boolean = () => true): RecordedRequest[] {
    return this.recorded.filter((entry) => entry.id > mark && predicate(entry));
  }

  find(method: string, path: string, afterMark = 0): RecordedRequest[] {
    return this.entriesAfter(afterMark, (entry) => entry.method === method && entry.path === path);
  }

  firstAfter(mark: number, method: string, path: string): RecordedRequest | undefined {
    return this.find(method, path, mark)[0];
  }

  /** Number of API-origin requests that are neither GET nor OPTIONS (spec L7). */
  writeRequestCount(): number {
    return this.recorded.filter((entry) => entry.method !== "GET" && entry.method !== "OPTIONS").length;
  }

  unauthorized401Seen(): boolean {
    return this.recorded.some((entry) => entry.status === 401);
  }

  visitedLogin(): boolean {
    return this.loginNavigationSeen;
  }

  /** A bearer-carrying request went to an origin other than the two allowlisted ones. */
  foreignAuthorizedRequestSeen(): boolean {
    return this.foreignAuthorizedCount > 0;
  }

  corsConsoleErrorsAfter(mark: number): number {
    return this.corsErrorTicks.filter((tick) => tick > mark).length;
  }

  /** API requests that failed at the network layer after `mark`. */
  requestFailuresAfter(mark: number): number {
    return this.recorded.filter((entry) => entry.failed && entry.failedTick !== null && entry.failedTick > mark).length;
  }

  private goodPortfolioEntries(): RecordedRequest[] {
    return this.recorded.filter(
      (entry) =>
        entry.method === "GET" &&
        entry.path === "/api/portfolio" &&
        entry.parsed?.type === "portfolio" &&
        entry.parsed.result.ok,
    );
  }

  /** The most recent identity-checked 200 GET /api/portfolio the page received. */
  lastGoodPortfolio(): PortfolioSnapshot | null {
    let latest: RecordedRequest | null = null;
    for (const entry of this.goodPortfolioEntries()) {
      if (latest === null || (entry.respondedTick ?? 0) > (latest.respondedTick ?? 0)) latest = entry;
    }
    if (latest === null || latest.parsed?.type !== "portfolio" || !latest.parsed.result.ok) return null;
    return latest.parsed.result.snapshot;
  }

  /** Wire-ordered ticker lists of every good portfolio response the page received. */
  portfolioWireOrders(): string[][] {
    const orders: string[][] = [];
    for (const entry of this.goodPortfolioEntries()) {
      if (entry.parsed?.type === "portfolio" && entry.parsed.result.ok) {
        orders.push(entry.parsed.result.snapshot.holdings.map((holding) => holding.ticker));
      }
    }
    return orders;
  }

  /**
   * Every GET /api/portfolio that has settled, in emission order. A read still in flight is not a load
   * outcome (a refetch in progress says nothing about whether the page loaded), so it is not listed.
   */
  settledPortfolioReads(): RecordedRequest[] {
    return this.find("GET", "/api/portfolio").filter((entry) => entry.settled);
  }

  /**
   * Ledger status of the LAST SETTLED GET /api/portfolio: 0 when it failed at the network layer, and 0 when
   * none has settled yet. The last entry of `settledPortfolioReads()` always carries this status, so a leg that
   * records those reads as its `http` and this value as `portfolioLoadStatus` cannot contradict itself.
   */
  lastPortfolioStatus(): number {
    const settled = this.settledPortfolioReads();
    return ledgerStatusOf(settled[settled.length - 1]);
  }

  /** Every page-side GET /api/portfolio and GET /api/market/prices recorded so far, settled or not. */
  pageReads(): RecordedRequest[] {
    return this.recorded.filter(
      (entry) => entry.method === "GET" && entry.path !== null && PACED_READ_PATHS.has(entry.path),
    );
  }

  priceObservationsAfter(mark: number): PriceObservation[] {
    return this.find("GET", "/api/market/prices", mark).map((entry) => ({
      tickers: entry.tickers,
      status: entry.status,
      settled: entry.settled,
      failed: entry.failed,
      arrayShaped: entry.parsed?.type === "prices" && entry.parsed.arrayShaped,
      nonNullPriceSeen: entry.parsed?.type === "prices" && entry.parsed.nonNullPriceSeen,
    }));
  }
}
