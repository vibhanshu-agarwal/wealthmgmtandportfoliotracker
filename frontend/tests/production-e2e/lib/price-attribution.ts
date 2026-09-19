/**
 * Wave 10.2 Step B (exit criterion 5b) - leg L6 price-request attribution arithmetic.
 *
 * Both the Portfolio page's own enrichment and the picker's draft-price query call
 * GET /api/market/prices in batches of 25 (portfolio.ts MARKET_PRICE_BATCH_SIZE).
 * The page batches tickers in the WIRE ORDER of its own GET /api/portfolio body;
 * the picker batches `[...tickers].sort()` (useDraftPrices.ts, default code-unit
 * sort, NOT localeCompare). To attribute a price request to the picker without
 * route interception, the verifier unchecks one held ticker T in the draft only:
 * the picker's next batches are then chunk25(sort(draft - T)), which must be
 * disjoint (as ordered batches) from the page's chunk25(wire order). This module
 * holds that arithmetic; ticker lists are used in memory only and never recorded.
 *
 * The leg then judges ONLY the picker-attributed requests: their count must equal the
 * predicted batch count (a duplicated picker batch fails), their statuses and shapes decide
 * the leg, and a page-side price request (one that is not a predicted picker batch) is never
 * attributed to route 9.3, whatever it answered.
 */
import type { FailureReason } from "./contract";
import { failureReasonForStatus } from "./ledger";
import type { HttpEntry } from "./ledger";

/** Mirrors MARKET_PRICE_BATCH_SIZE in src/lib/api/portfolio.ts (a static guard compares the two). */
export const PRICE_BATCH_SIZE = 25;

export function chunk<T>(items: readonly T[], size: number): T[][] {
  if (!Number.isInteger(size) || size < 1) throw new RangeError("chunk size must be a positive integer");
  const batches: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    batches.push(items.slice(index, index + size));
  }
  return batches;
}

export function chunk25<T>(items: readonly T[]): T[][] {
  return chunk(items, PRICE_BATCH_SIZE);
}

/** The app's own ordering: JavaScript's default sort (UTF-16 code units). Never localeCompare. */
export function sortLikeApp(items: readonly string[]): string[] {
  return [...items].sort();
}

/** First-occurrence-preserving de-duplication, as `[...new Set(...)]` in the app. */
export function uniqueInOrder(items: readonly string[]): string[] {
  return [...new Set(items)];
}

/** The request identity of one batch: the ordered, comma-joined ticker list. */
export function batchKey(batch: readonly string[]): string {
  return batch.join(",");
}

/** Ordered tickers of a GET /api/market/prices URL, query-decoded. Empty when absent or unparseable. */
export function tickersFromPricesUrl(rawUrl: string): string[] {
  try {
    const param = new URL(rawUrl).searchParams.get("tickers");
    if (param === null || param.trim() === "") return [];
    return param
      .split(",")
      .map((ticker) => ticker.trim())
      .filter((ticker) => ticker !== "");
  } catch {
    return [];
  }
}

/** Batches the picker will request once `unchecked` is removed from the draft. */
export function predictPickerBatches(draftTickers: readonly string[], unchecked: string): string[][] {
  return chunk25(sortLikeApp(uniqueInOrder(draftTickers).filter((ticker) => ticker !== unchecked)));
}

/** Batches the page's own enrichment requests for a portfolio in the given wire order. */
export function pageBatches(wireOrder: readonly string[]): string[][] {
  return chunk25(uniqueInOrder(wireOrder));
}

/** True when no batch in `a` has the same identity as a batch in `b`. */
export function areDisjoint(a: readonly (readonly string[])[], b: readonly (readonly string[])[]): boolean {
  const keys = new Set(b.map(batchKey));
  return a.every((batch) => !keys.has(batchKey(batch)));
}

export interface Attribution {
  /** The held ticker to uncheck (draft only). */
  readonly ticker: string;
  readonly predicted: readonly (readonly string[])[];
  /** Every page-side batch the prediction was proven disjoint from. */
  readonly pageBatches: readonly (readonly string[])[];
}

/**
 * Picks the alphabetically first held ticker whose removal yields picker batches
 * disjoint from every page batch, or null when no held ticker does (the leg then
 * fails closed; the requirement is never weakened).
 *
 * `otherWireOrders` are earlier observations of the page's own portfolio response;
 * their batches are treated as page batches too, so a reordered refetch cannot
 * make a picker batch look page-owned or vice versa.
 */
export function chooseAttributableTicker(
  latestWireOrder: readonly string[],
  otherWireOrders: readonly (readonly string[])[] = [],
): Attribution | null {
  const held = uniqueInOrder(latestWireOrder);
  const knownPageBatches = [latestWireOrder, ...otherWireOrders].flatMap((order) => pageBatches(order));

  for (const ticker of sortLikeApp(held)) {
    const predicted = predictPickerBatches(held, ticker);
    if (predicted.length > 0 && areDisjoint(predicted, knownPageBatches)) {
      return { ticker, predicted, pageBatches: knownPageBatches };
    }
  }
  return null;
}

export interface PriceObservation {
  readonly tickers: readonly string[];
  /** Null until a response event arrived. */
  readonly status: number | null;
  /** True once the response was read (or the request failed). */
  readonly settled: boolean;
  readonly failed: boolean;
  readonly arrayShaped: boolean;
  readonly nonNullPriceSeen: boolean;
}

export interface PriceEvaluation {
  readonly predictedBatchCount: number;
  /** How many picker-ATTRIBUTED requests were emitted after the uncheck. Page-side ones are not counted. */
  readonly attributedCount: number;
  /** The ledger fact of the same name: the attributed request count, equal to `attributedCount`. */
  readonly priceRequestsAfterUncheck: number;
  /** Every attributed request has settled. */
  readonly allSettled: boolean;
  /** An attributed request failed at the network layer. */
  readonly anyFailed: boolean;
  /** The first settled non-200 status among the attributed requests, if any. */
  readonly firstBadStatus: number | null;
  /** All attributed requests answered 200 (and there is at least one). */
  readonly allStatus200: boolean;
  readonly arrayShaped: boolean;
  /** At least one non-null numeric price in a picker-attributed response. */
  readonly nonNullPriceSeen: boolean;
  readonly allPredictedBatchesSeen: boolean;
  /** Everything expected has arrived and been read: safe to stop waiting. */
  readonly complete: boolean;
}

/** The requests the picker itself issued: exactly those whose batch is one of the predicted batches. */
export function attributedObservations(
  observations: readonly PriceObservation[],
  predicted: readonly (readonly string[])[],
): PriceObservation[] {
  const predictedKeys = new Set(predicted.map(batchKey));
  return observations.filter((observation) => predictedKeys.has(batchKey(observation.tickers)));
}

/**
 * Evaluates the price requests emitted after the uncheck. `observations` must
 * already be limited to requests issued after the uncheck; responses to earlier
 * requests are excluded by the caller and never reach this function. Only the
 * attributed (picker) requests are judged.
 */
export function evaluatePriceObservations(
  observations: readonly PriceObservation[],
  predicted: readonly (readonly string[])[],
): PriceEvaluation {
  const attributed = attributedObservations(observations, predicted);
  const seenKeys = new Set(attributed.map((observation) => batchKey(observation.tickers)));

  const allSettled = attributed.every((observation) => observation.settled);
  const anyFailed = attributed.some((observation) => observation.failed);
  const badStatus = attributed.find(
    (observation) => observation.settled && !observation.failed && observation.status !== 200,
  );
  const allPredictedBatchesSeen = predicted.length > 0 && predicted.every((batch) => seenKeys.has(batchKey(batch)));

  return {
    predictedBatchCount: predicted.length,
    attributedCount: attributed.length,
    priceRequestsAfterUncheck: attributed.length,
    allSettled,
    anyFailed,
    firstBadStatus: badStatus === undefined ? null : badStatus.status,
    allStatus200:
      attributed.length > 0 &&
      attributed.every((observation) => observation.settled && !observation.failed && observation.status === 200),
    arrayShaped: attributed.length > 0 && attributed.every((observation) => observation.arrayShaped),
    nonNullPriceSeen: attributed.some((observation) => observation.nonNullPriceSeen),
    allPredictedBatchesSeen,
    complete: allSettled && allPredictedBatchesSeen,
  };
}

/**
 * The ledger `http` entries for the L6 leg: the attributed picker requests only. A page-side price
 * request is deliberately absent, because every recorded status on a leg is judged (a 409, 429, 503
 * or 504 fails it) and a page-side failure must not be attributed to route 9.3. Status 0 means the
 * request failed or had no response yet.
 */
export function priceHttpEntries(
  observations: readonly PriceObservation[],
  predicted: readonly (readonly string[])[],
): HttpEntry[] {
  return attributedObservations(observations, predicted).map((observation) => ({
    method: "GET",
    path: "/api/market/prices",
    status: observation.failed ? 0 : (observation.status ?? 0),
  }));
}

/**
 * The failure reason L6 records for an evaluation, or null when route 9.3 passed. The first
 * failing condition decides. A duplicated picker batch fails even though every predicted batch
 * was seen: the attributed count must equal the predicted batch count exactly.
 */
export function priceLegFailure(evaluation: PriceEvaluation, unchecked: boolean): FailureReason | null {
  if (!unchecked) return "ASSERTION_FAILED";
  if (!evaluation.allPredictedBatchesSeen || !evaluation.allSettled) return "TIMEOUT";
  if (evaluation.anyFailed) return "REQUEST_FAILED";
  if (evaluation.firstBadStatus !== null) return failureReasonForStatus(evaluation.firstBadStatus);
  if (evaluation.attributedCount !== evaluation.predictedBatchCount) return "ASSERTION_FAILED";
  if (!evaluation.allStatus200 || !evaluation.arrayShaped || !evaluation.nonNullPriceSeen) return "ASSERTION_FAILED";
  return null;
}
