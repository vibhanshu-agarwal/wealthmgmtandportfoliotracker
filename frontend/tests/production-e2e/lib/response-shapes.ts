/**
 * Wave 10.2 Step B (exit criterion 5b) - pure parsers for the response and request
 * bodies the browser legs reason about, plus the golden-state comparisons.
 *
 * Bodies are read in memory only. Nothing returned from here is ever written to
 * the ledger except as a boolean, an integer or a closed-vocabulary value.
 */
import type { FreshnessState } from "./contract";
import { hasExactKeys, isNonNegativeInteger, isRecord } from "./guards";

// -- holdings ----------------------------------------------------------------

export interface Holding {
  readonly ticker: string;
  readonly quantity: string;
}

/** ASCII / UTF-16 code-unit order, matching the golden file's sort and the app's default `.sort()`. */
export function compareTickers(a: string, b: string): number {
  if (a < b) return -1;
  if (a > b) return 1;
  return 0;
}

export function sortHoldings(holdings: readonly Holding[]): Holding[] {
  return [...holdings].sort((a, b) => compareTickers(a.ticker, b.ticker));
}

/** Sorted (ticker, quantity) pairs compared as strings; duplicates on either side never match. */
export function holdingPairsEqual(a: readonly Holding[], b: readonly Holding[]): boolean {
  if (a.length !== b.length) return false;
  const left = sortHoldings(a);
  const right = sortHoldings(b);
  return left.every((holding, index) => {
    const other = right[index];
    return holding.ticker === other.ticker && holding.quantity === other.quantity;
  });
}

function hasDuplicateTickers(holdings: readonly Holding[]): boolean {
  return new Set(holdings.map((holding) => holding.ticker)).size !== holdings.length;
}

// -- portfolio ---------------------------------------------------------------

export interface PortfolioSnapshot {
  readonly userId: string;
  readonly version: number;
  /** Holdings in wire order (assetTicker normalized to `ticker`). */
  readonly holdings: readonly Holding[];
}

export type PortfolioParseCode = "NOT_ARRAY" | "NOT_OBJECT" | "IDENTITY_MISMATCH" | "MALFORMED_PORTFOLIO";

export type PortfolioParseResult =
  | { readonly ok: true; readonly snapshot: PortfolioSnapshot }
  | { readonly ok: false; readonly code: PortfolioParseCode };

function parseWireHoldings(raw: unknown): Holding[] | null {
  if (!Array.isArray(raw)) return null;
  const holdings: Holding[] = [];
  for (const entry of raw) {
    if (!isRecord(entry)) return null;
    const ticker = entry.assetTicker;
    const quantity = entry.quantity;
    // A JSON-number quantity has lost its wire formatting; only strings carry fidelity.
    if (typeof ticker !== "string" || ticker === "" || typeof quantity !== "string") return null;
    holdings.push({ ticker, quantity });
  }
  return hasDuplicateTickers(holdings) ? null : holdings;
}

function snapshotFrom(entry: Record<string, unknown>, userId: string): PortfolioParseResult {
  const holdings = parseWireHoldings(entry.holdings);
  if (!isNonNegativeInteger(entry.version) || holdings === null) {
    return { ok: false, code: "MALFORMED_PORTFOLIO" };
  }
  return { ok: true, snapshot: { userId, version: entry.version, holdings } };
}

/**
 * GET /api/portfolio: a list with exactly one entry whose userId is `userId`. Zero
 * or several matches fail (never "the first element").
 */
export function parsePortfolioList(body: unknown, userId: string): PortfolioParseResult {
  if (!Array.isArray(body)) return { ok: false, code: "NOT_ARRAY" };
  const matches = body.filter((entry) => isRecord(entry) && entry.userId === userId);
  if (matches.length !== 1) return { ok: false, code: "IDENTITY_MISMATCH" };
  return snapshotFrom(matches[0] as Record<string, unknown>, userId);
}

/** A single PortfolioResponse object, as returned by PUT /api/portfolio/holdings and demo-reset. */
export function parsePortfolioObject(body: unknown, userId: string): PortfolioParseResult {
  if (!isRecord(body)) return { ok: false, code: "NOT_OBJECT" };
  if (body.userId !== userId) return { ok: false, code: "IDENTITY_MISMATCH" };
  return snapshotFrom(body, userId);
}

// -- golden file -------------------------------------------------------------

export const GOLDEN_SCHEMA = "wave10-5b-golden-v1";

export interface GoldenState {
  readonly catalogSha256: string;
  /** Sorted by ticker (ASCII), 8-decimal string quantities. */
  readonly holdings: readonly Holding[];
  readonly activeTickers: readonly string[];
}

export class GoldenFileError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.name = "GoldenFileError";
    this.code = code;
  }
}

const EIGHT_DP_QUANTITY = /^(\d{1,11})\.(\d{8})$/;
const SHA256_HEX = /^[0-9A-Fa-f]{64}$/;

/** Strict parse of the orchestrator's golden file. Any deviation throws (fail closed). */
export function parseGoldenFile(text: string): GoldenState {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new GoldenFileError("GOLDEN_NOT_JSON");
  }
  if (!isRecord(raw) || !hasExactKeys(raw, ["schema", "catalogSha256", "holdings", "activeTickers"])) {
    throw new GoldenFileError("GOLDEN_KEYS");
  }
  if (raw.schema !== GOLDEN_SCHEMA) throw new GoldenFileError("GOLDEN_SCHEMA");
  if (typeof raw.catalogSha256 !== "string" || !SHA256_HEX.test(raw.catalogSha256)) {
    throw new GoldenFileError("GOLDEN_CATALOG_SHA");
  }
  if (!Array.isArray(raw.holdings) || raw.holdings.length === 0) throw new GoldenFileError("GOLDEN_HOLDINGS");

  const holdings: Holding[] = [];
  for (const entry of raw.holdings) {
    if (!isRecord(entry) || !hasExactKeys(entry, ["assetTicker", "quantity"])) {
      throw new GoldenFileError("GOLDEN_HOLDING_SHAPE");
    }
    const ticker = entry.assetTicker;
    const quantity = entry.quantity;
    if (typeof ticker !== "string" || ticker === "" || typeof quantity !== "string" || !EIGHT_DP_QUANTITY.test(quantity)) {
      throw new GoldenFileError("GOLDEN_HOLDING_VALUE");
    }
    holdings.push({ ticker, quantity });
  }
  for (let index = 1; index < holdings.length; index += 1) {
    if (compareTickers(holdings[index - 1].ticker, holdings[index].ticker) >= 0) {
      throw new GoldenFileError("GOLDEN_HOLDINGS_NOT_SORTED_UNIQUE");
    }
  }

  const active = raw.activeTickers;
  if (
    !Array.isArray(active) ||
    active.length === 0 ||
    active.some((ticker) => typeof ticker !== "string" || ticker === "") ||
    new Set(active).size !== active.length
  ) {
    throw new GoldenFileError("GOLDEN_ACTIVE_TICKERS");
  }

  return { catalogSha256: raw.catalogSha256, holdings, activeTickers: active as string[] };
}

// -- the L8 edit -------------------------------------------------------------

export class QuantityError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.name = "QuantityError";
    this.code = code;
  }
}

/**
 * Adds exactly 1 to an 8-decimal quantity string without floating point: only the
 * integer digits change, and the domain limit of 11 integer digits is enforced.
 */
export function addOneToQuantity(quantity: string): string {
  const match = EIGHT_DP_QUANTITY.exec(quantity);
  if (match === null) throw new QuantityError("QUANTITY_NOT_EIGHT_DP");
  const integerPart = String(Number(match[1]) + 1);
  if (integerPart.length > 11) throw new QuantityError("QUANTITY_OVERFLOW");
  return `${integerPart}.${match[2]}`;
}

/** AAPL when golden holds it, otherwise the first golden ticker. */
export function chooseEditTicker(golden: GoldenState): string {
  return golden.holdings.some((holding) => holding.ticker === "AAPL") ? "AAPL" : golden.holdings[0].ticker;
}

export interface ExpectedDraft {
  readonly ticker: string;
  readonly fromQuantity: string;
  readonly toQuantity: string;
  /** The full expected draft: golden with exactly one quantity replaced. */
  readonly holdings: readonly Holding[];
}

export function buildExpectedDraft(golden: GoldenState, ticker: string): ExpectedDraft {
  const target = golden.holdings.find((holding) => holding.ticker === ticker);
  if (target === undefined) throw new QuantityError("EDIT_TICKER_NOT_IN_GOLDEN");
  const toQuantity = addOneToQuantity(target.quantity);
  return {
    ticker,
    fromQuantity: target.quantity,
    toQuantity,
    holdings: golden.holdings.map((holding) => (holding.ticker === ticker ? { ticker, quantity: toQuantity } : holding)),
  };
}

// -- request bodies ----------------------------------------------------------

/** Parses a captured request body; undefined when absent or not JSON. */
export function parseRequestJson(text: string | null): unknown {
  if (text === null) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * The save request body is exactly {expectedVersion: integer, holdings: [{ticker,
 * quantity}]} and its holdings equal the expected full draft (sorted string pairs).
 * The version is compared separately by the caller.
 */
export function putBodyMatchesDraft(body: unknown, expected: readonly Holding[]): boolean {
  if (!isRecord(body) || !hasExactKeys(body, ["expectedVersion", "holdings"])) return false;
  if (!isNonNegativeInteger(body.expectedVersion) || !Array.isArray(body.holdings)) return false;
  const holdings: Holding[] = [];
  for (const entry of body.holdings) {
    if (!isRecord(entry) || !hasExactKeys(entry, ["ticker", "quantity"])) return false;
    if (typeof entry.ticker !== "string" || typeof entry.quantity !== "string") return false;
    holdings.push({ ticker: entry.ticker, quantity: entry.quantity });
  }
  return !hasDuplicateTickers(holdings) && holdingPairsEqual(holdings, expected);
}

/** The reset request body is exactly {expectedVersion: <expected>}. */
export function resetBodyMatches(body: unknown, expectedVersion: number): boolean {
  return isRecord(body) && hasExactKeys(body, ["expectedVersion"]) && body.expectedVersion === expectedVersion;
}

// -- read-only endpoint bodies ------------------------------------------------

export interface SummaryFreshness {
  /** ABSENT when the field is missing or its state is not one of the four known states. */
  readonly state: FreshnessState;
  readonly countsValid: boolean;
}

export function parseSummaryFreshness(body: unknown): SummaryFreshness {
  const absent: SummaryFreshness = { state: "ABSENT", countsValid: false };
  if (!isRecord(body) || !isRecord(body.assetPriceFreshness)) return absent;
  const freshness = body.assetPriceFreshness;
  const state = freshness.state;
  const known = state === "FRESH" || state === "STALE" || state === "UNKNOWN" || state === "MISSING";
  return {
    state: known ? state : "ABSENT",
    countsValid:
      isNonNegativeInteger(freshness.staleHoldings) &&
      isNonNegativeInteger(freshness.unknownPriceHoldings) &&
      isNonNegativeInteger(freshness.missingPriceHoldings),
  };
}

export interface CatalogShape {
  /** True only when catalogVersion is a non-empty string AND assets is a non-empty, well-formed array. */
  readonly assetsNonEmpty: boolean;
  readonly activeTickers: readonly string[];
}

export function parseCatalog(body: unknown): CatalogShape {
  const empty: CatalogShape = { assetsNonEmpty: false, activeTickers: [] };
  if (!isRecord(body)) return empty;
  const versionOk = typeof body.catalogVersion === "string" && body.catalogVersion !== "";
  if (!Array.isArray(body.assets) || body.assets.length === 0) return empty;

  const active: string[] = [];
  for (const asset of body.assets) {
    if (!isRecord(asset) || typeof asset.ticker !== "string" || typeof asset.lifecycleStatus !== "string") {
      return empty;
    }
    if (asset.lifecycleStatus === "ACTIVE") active.push(asset.ticker);
  }
  return { assetsNonEmpty: versionOk, activeTickers: active };
}

/** Order-insensitive equality of two ticker sets (no duplicates on either side). */
export function sameTickerSet(a: readonly string[], b: readonly string[]): boolean {
  if (new Set(a).size !== a.length || new Set(b).size !== b.length) return false;
  if (a.length !== b.length) return false;
  const right = new Set(b);
  return a.every((ticker) => right.has(ticker));
}

/** The boolean, or null when the body is not the documented {anotherSessionActive: boolean}. */
export function parsePresence(body: unknown): boolean | null {
  return isRecord(body) && typeof body.anotherSessionActive === "boolean" ? body.anotherSessionActive : null;
}

export interface PriceRowsShape {
  readonly arrayShaped: boolean;
  /** At least one row with a finite numeric currentPrice that is not flagged unavailable. */
  readonly nonNullPriceSeen: boolean;
}

export function parsePriceRows(body: unknown): PriceRowsShape {
  if (!Array.isArray(body) || body.some((row) => !isRecord(row) || typeof row.ticker !== "string")) {
    return { arrayShaped: false, nonNullPriceSeen: false };
  }
  const rows = body as Array<Record<string, unknown>>;
  return {
    arrayShaped: true,
    nonNullPriceSeen: rows.some(
      (row) => typeof row.currentPrice === "number" && Number.isFinite(row.currentPrice) && row.priceUnavailable !== true,
    ),
  };
}
