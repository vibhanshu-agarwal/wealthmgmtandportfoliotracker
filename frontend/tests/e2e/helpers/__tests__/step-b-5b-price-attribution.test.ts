// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  areDisjoint,
  attributedObservations,
  batchKey,
  chooseAttributableTicker,
  chunk,
  chunk25,
  evaluatePriceObservations,
  pageBatches,
  PRICE_BATCH_SIZE,
  predictPickerBatches,
  priceHttpEntries,
  priceLegFailure,
  sortLikeApp,
  tickersFromPricesUrl,
  uniqueInOrder,
  type PriceObservation,
} from "../../../production-e2e/lib/price-attribution";
import { forbiddenStatusFailure } from "../../../production-e2e/lib/leg-runner";

/** T000, T001 ... so that default and numeric order agree and batches are easy to reason about. */
function tickers(count: number, prefix = "T"): string[] {
  return Array.from({ length: count }, (_, index) => `${prefix}${String(index).padStart(3, "0")}`);
}

describe("Step B 5b chunking", () => {
  it("uses the app's batch size of 25", () => {
    expect(PRICE_BATCH_SIZE).toBe(25);
  });

  it.each([
    [0, []],
    [1, [1]],
    [24, [24]],
    [25, [25]],
    [26, [25, 1]],
    [50, [25, 25]],
    [159, [25, 25, 25, 25, 25, 25, 9]],
  ])("chunk25 of %i items has batch sizes %j", (count, sizes) => {
    expect(chunk25(tickers(count)).map((batch) => batch.length)).toEqual(sizes);
  });

  it("keeps order and rejects a non-positive size", () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(() => chunk([1], 0)).toThrow(RangeError);
    expect(() => chunk([1], 1.5)).toThrow(RangeError);
  });
});

describe("Step B 5b ordering", () => {
  it("sorts by UTF-16 code unit like the app's default sort, not by locale", () => {
    const input = ["b", "A", "a", "B", "^X", "a.b", "a-b", "Z"];
    expect(sortLikeApp(input)).toEqual([...input].sort());
    // localeCompare would interleave case; the default sort puts every upper-case letter first.
    expect(sortLikeApp(input).slice(0, 3)).toEqual(["A", "B", "Z"]);
    expect(sortLikeApp(input)).not.toEqual([...input].sort((a, b) => a.localeCompare(b)));
  });

  it("does not mutate its input and de-duplicates in first-occurrence order", () => {
    const input = ["B", "A", "B", "C", "A"];
    expect(sortLikeApp(input)).toEqual(["A", "A", "B", "B", "C"]);
    expect(input).toEqual(["B", "A", "B", "C", "A"]);
    expect(uniqueInOrder(input)).toEqual(["B", "A", "C"]);
  });
});

describe("Step B 5b prediction", () => {
  it("predicts chunk25(sort(draft minus T)) and shifts every batch when the first ticker leaves", () => {
    const held = tickers(60);
    const predicted = predictPickerBatches(held, "T000");
    expect(predicted.map((batch) => batch.length)).toEqual([25, 25, 9]);
    expect(predicted[0][0]).toBe("T001");
    expect(predicted.flat()).not.toContain("T000");
    // The page's batches (same order here) begin at T000, so no batch is shared.
    expect(areDisjoint(predicted, pageBatches(held))).toBe(true);
  });

  it("is order-independent for the draft because the picker sorts it", () => {
    const held = tickers(30);
    expect(predictPickerBatches([...held].reverse(), "T003")).toEqual(predictPickerBatches(held, "T003"));
  });

  it("page batches follow wire order, not sorted order", () => {
    const wire = ["Z1", "A1", "M1"];
    expect(pageBatches(wire)).toEqual([["Z1", "A1", "M1"]]);
    expect(batchKey(pageBatches(wire)[0])).toBe("Z1,A1,M1");
  });

  it("identifies a batch by its ordered ticker list", () => {
    expect(batchKey(["A", "B"])).not.toBe(batchKey(["B", "A"]));
    expect(areDisjoint([["A", "B"]], [["B", "A"]])).toBe(true);
    expect(areDisjoint([["A", "B"]], [["A", "B"]])).toBe(false);
  });
});

describe("Step B 5b ticker choice", () => {
  it("chooses the alphabetically first held ticker when the wire order is already sorted", () => {
    const wire = tickers(159);
    const choice = chooseAttributableTicker(wire);
    expect(choice?.ticker).toBe("T000");
    expect(choice?.predicted).toHaveLength(7);
    expect(choice?.predicted[0]).toHaveLength(25);
    expect(choice?.predicted[6]).toHaveLength(8);
  });

  it("still chooses the alphabetically first ticker for an unsorted wire order that shares no batch", () => {
    const wire = [...tickers(60)].reverse();
    const choice = chooseAttributableTicker(wire);
    expect(choice?.ticker).toBe("T000");
    expect(areDisjoint(choice!.predicted, choice!.pageBatches)).toBe(true);
  });

  it("scans on to the next ticker when the first one collides with a page batch", () => {
    // Sorted wire order ["A","B"]: page batch "A,B". Removing A predicts ["B"], but another
    // observed page response already requested exactly ["B"], so A cannot be attributed.
    const choice = chooseAttributableTicker(["A", "B"], [["B"]]);
    expect(choice?.ticker).toBe("B");
    expect(choice?.predicted).toEqual([["A"]]);
  });

  it("fails closed (null) when no held ticker yields disjoint batches", () => {
    expect(chooseAttributableTicker(["A", "B"], [["B"], ["A"]])).toBeNull();
  });

  it("returns null when removing a ticker would leave an empty draft", () => {
    expect(chooseAttributableTicker(["ONLY"])).toBeNull();
    expect(chooseAttributableTicker([])).toBeNull();
  });

  it("treats duplicate wire tickers as one held ticker", () => {
    expect(chooseAttributableTicker(["A", "A", "B"])?.ticker).toBe("A");
  });

  it("never records or returns anything but the in-memory lists it was given", () => {
    const choice = chooseAttributableTicker(tickers(30));
    expect(Object.keys(choice!).sort()).toEqual(["pageBatches", "predicted", "ticker"]);
  });
});

describe("Step B 5b price URL parsing", () => {
  it("decodes the ordered ticker list", () => {
    expect(tickersFromPricesUrl("https://api.example/api/market/prices?tickers=B%2CA%2CC.NS%2CX-Y")).toEqual([
      "B",
      "A",
      "C.NS",
      "X-Y",
    ]);
    expect(tickersFromPricesUrl("https://api.example/api/market/prices?tickers=B,A")).toEqual(["B", "A"]);
  });

  it("returns an empty list for a missing, blank or unparseable value", () => {
    expect(tickersFromPricesUrl("https://api.example/api/market/prices")).toEqual([]);
    expect(tickersFromPricesUrl("https://api.example/api/market/prices?tickers=")).toEqual([]);
    expect(tickersFromPricesUrl("https://api.example/api/market/prices?tickers=%2C%2C")).toEqual([]);
    expect(tickersFromPricesUrl("not a url")).toEqual([]);
  });
});

describe("Step B 5b price evaluation", () => {
  const predicted = [["B", "C"], ["D"]];
  const good = (tickerList: string[], overrides: Partial<PriceObservation> = {}): PriceObservation => ({
    tickers: tickerList,
    status: 200,
    settled: true,
    failed: false,
    arrayShaped: true,
    nonNullPriceSeen: true,
    ...overrides,
  });

  it("passes when every predicted batch answered 200, array-shaped, with a non-null price", () => {
    const evaluation = evaluatePriceObservations([good(["B", "C"]), good(["D"])], predicted);
    expect(evaluation).toMatchObject({
      predictedBatchCount: 2,
      attributedCount: 2,
      priceRequestsAfterUncheck: 2,
      allSettled: true,
      anyFailed: false,
      firstBadStatus: null,
      allStatus200: true,
      arrayShaped: true,
      nonNullPriceSeen: true,
      allPredictedBatchesSeen: true,
      complete: true,
    });
  });

  it("is incomplete while a predicted batch has not been requested yet", () => {
    const evaluation = evaluatePriceObservations([good(["B", "C"])], predicted);
    expect(evaluation.allPredictedBatchesSeen).toBe(false);
    expect(evaluation.complete).toBe(false);
  });

  it("is incomplete while a request is unsettled", () => {
    const pending = good(["D"], { status: null, settled: false, arrayShaped: false, nonNullPriceSeen: false });
    const evaluation = evaluatePriceObservations([good(["B", "C"]), pending], predicted);
    expect(evaluation.allSettled).toBe(false);
    expect(evaluation.complete).toBe(false);
    expect(evaluation.allStatus200).toBe(false);
  });

  it.each([429, 409, 503, 504, 500, 401])("reports a %i as the first bad status and not all-200", (status) => {
    const evaluation = evaluatePriceObservations(
      [good(["B", "C"]), good(["D"], { status, arrayShaped: false, nonNullPriceSeen: false })],
      predicted,
    );
    expect(evaluation.firstBadStatus).toBe(status);
    expect(evaluation.allStatus200).toBe(false);
    expect(evaluation.arrayShaped).toBe(false);
  });

  it("flags a failed request", () => {
    const failed = good(["D"], { status: null, settled: true, failed: true, arrayShaped: false, nonNullPriceSeen: false });
    const evaluation = evaluatePriceObservations([good(["B", "C"]), failed], predicted);
    expect(evaluation.anyFailed).toBe(true);
    expect(evaluation.allStatus200).toBe(false);
    expect(evaluation.firstBadStatus).toBeNull();
  });

  it("requires the non-null price to come from a picker-attributed response", () => {
    const pageOwned = good(["X", "Y"], { nonNullPriceSeen: true });
    const evaluation = evaluatePriceObservations(
      [good(["B", "C"], { nonNullPriceSeen: false }), good(["D"], { nonNullPriceSeen: false }), pageOwned],
      predicted,
    );
    expect(evaluation.nonNullPriceSeen).toBe(false);
    // The page-owned request is not attributed to the picker, so it is not counted.
    expect(evaluation.attributedCount).toBe(2);
    expect(evaluation.priceRequestsAfterUncheck).toBe(2);
  });

  it("counts a non-array response as not array-shaped even when the status is 200", () => {
    const evaluation = evaluatePriceObservations(
      [good(["B", "C"]), good(["D"], { arrayShaped: false })],
      predicted,
    );
    expect(evaluation.allStatus200).toBe(true);
    expect(evaluation.arrayShaped).toBe(false);
  });

  it("has nothing to attribute for zero predicted batches or zero observations", () => {
    expect(evaluatePriceObservations([good(["B"])], []).complete).toBe(false);
    const empty = evaluatePriceObservations([], predicted);
    expect(empty.allStatus200).toBe(false);
    expect(empty.arrayShaped).toBe(false);
    expect(empty.complete).toBe(false);
  });
});

describe("Step B 5b price evaluation judges only the picker-attributed requests", () => {
  const predicted = [["B", "C"], ["D"]];
  const good = (tickerList: string[], overrides: Partial<PriceObservation> = {}): PriceObservation => ({
    tickers: tickerList,
    status: 200,
    settled: true,
    failed: false,
    arrayShaped: true,
    nonNullPriceSeen: true,
    ...overrides,
  });
  const pageSide = (overrides: Partial<PriceObservation> = {}) => good(["X", "Y"], overrides);

  it("counts equal the predicted batch count on success, and the fact equals the attributed count", () => {
    const evaluation = evaluatePriceObservations([good(["B", "C"]), good(["D"])], predicted);
    expect(evaluation.attributedCount).toBe(predicted.length);
    expect(evaluation.priceRequestsAfterUncheck).toBe(evaluation.attributedCount);
    expect(evaluation.predictedBatchCount).toBe(predicted.length);
    expect(priceLegFailure(evaluation, true)).toBeNull();
  });

  it("counts only attributed requests when page-side requests are interleaved", () => {
    const evaluation = evaluatePriceObservations(
      [pageSide(), good(["B", "C"]), pageSide(), good(["D"]), pageSide()],
      predicted,
    );
    expect(evaluation.attributedCount).toBe(2);
    expect(evaluation.priceRequestsAfterUncheck).toBe(2);
    expect(priceLegFailure(evaluation, true)).toBeNull();
    const kept = attributedObservations([pageSide(), good(["B", "C"]), good(["D"])], predicted);
    expect(kept.map((observation) => batchKey(observation.tickers))).toEqual(["B,C", "D"]);
  });

  it("a duplicated picker batch fails, even though every predicted batch was seen and answered 200", () => {
    const evaluation = evaluatePriceObservations([good(["B", "C"]), good(["B", "C"]), good(["D"])], predicted);
    expect(evaluation.allPredictedBatchesSeen).toBe(true);
    expect(evaluation.allStatus200).toBe(true);
    expect(evaluation.arrayShaped).toBe(true);
    expect(evaluation.nonNullPriceSeen).toBe(true);
    expect(evaluation.attributedCount).toBe(3);
    expect(evaluation.predictedBatchCount).toBe(2);
    expect(priceLegFailure(evaluation, true)).toBe("ASSERTION_FAILED");
  });

  it("fewer attributed requests than predicted times out rather than passing", () => {
    const evaluation = evaluatePriceObservations([good(["B", "C"])], predicted);
    expect(priceLegFailure(evaluation, true)).toBe("TIMEOUT");
  });

  it.each([429, 409, 503, 504, 500, 401])("a page-side %i does not fail the leg and is not attributed to it", (status) => {
    const evaluation = evaluatePriceObservations(
      [good(["B", "C"]), good(["D"]), pageSide({ status, arrayShaped: false, nonNullPriceSeen: false })],
      predicted,
    );
    expect(evaluation.firstBadStatus).toBeNull();
    expect(evaluation.allStatus200).toBe(true);
    expect(evaluation.arrayShaped).toBe(true);
    expect(evaluation.anyFailed).toBe(false);
    expect(evaluation.allSettled).toBe(true);
    expect(evaluation.attributedCount).toBe(2);
    expect(priceLegFailure(evaluation, true)).toBeNull();
  });

  it("a page-side request that failed or has not settled does not fail or hold up the leg", () => {
    const failedPage = pageSide({ status: null, settled: true, failed: true, arrayShaped: false, nonNullPriceSeen: false });
    const pendingPage = pageSide({ status: null, settled: false, arrayShaped: false, nonNullPriceSeen: false });
    const evaluation = evaluatePriceObservations([good(["B", "C"]), good(["D"]), failedPage, pendingPage], predicted);
    expect(evaluation.anyFailed).toBe(false);
    expect(evaluation.allSettled).toBe(true);
    expect(evaluation.complete).toBe(true);
    expect(priceLegFailure(evaluation, true)).toBeNull();
  });

  it.each([
    [409, "CONFLICT"],
    [429, "RATE_LIMITED"],
    [503, "HTTP_STATUS_NOT_200"],
    [504, "HTTP_STATUS_NOT_200"],
    [500, "HTTP_STATUS_NOT_200"],
    [401, "HTTP_STATUS_NOT_200"],
  ])("a %i on an ATTRIBUTED picker request fails the leg with its own reason", (status, reason) => {
    const observations = [good(["B", "C"]), good(["D"], { status, arrayShaped: false, nonNullPriceSeen: false })];
    const evaluation = evaluatePriceObservations(observations, predicted);
    expect(evaluation.firstBadStatus).toBe(status);
    expect(priceLegFailure(evaluation, true)).toBe(reason);
  });

  it("an attributed request that failed at the network layer fails the leg as REQUEST_FAILED", () => {
    const failed = good(["D"], { status: null, settled: true, failed: true, arrayShaped: false, nonNullPriceSeen: false });
    expect(priceLegFailure(evaluatePriceObservations([good(["B", "C"]), failed], predicted), true)).toBe("REQUEST_FAILED");
  });

  it("an unsettled attributed request times out, and an unchecked box fails first", () => {
    const pending = good(["D"], { status: null, settled: false, arrayShaped: false, nonNullPriceSeen: false });
    expect(priceLegFailure(evaluatePriceObservations([good(["B", "C"]), pending], predicted), true)).toBe("TIMEOUT");
    expect(priceLegFailure(evaluatePriceObservations([good(["B", "C"]), good(["D"])], predicted), false)).toBe(
      "ASSERTION_FAILED",
    );
  });

  it("shape and price failures on attributed responses fail the leg", () => {
    const notArray = evaluatePriceObservations([good(["B", "C"]), good(["D"], { arrayShaped: false })], predicted);
    expect(priceLegFailure(notArray, true)).toBe("ASSERTION_FAILED");
    const noPrice = evaluatePriceObservations(
      [good(["B", "C"], { nonNullPriceSeen: false }), good(["D"], { nonNullPriceSeen: false })],
      predicted,
    );
    expect(priceLegFailure(noPrice, true)).toBe("ASSERTION_FAILED");
  });

  it("records only the attributed requests, so the leg runner forbidden-status downgrade sees exactly them", () => {
    const observations = [
      good(["B", "C"]),
      good(["D"]),
      pageSide({ status: 429 }),
      pageSide({ status: 0, settled: true, failed: true }),
    ];
    expect(priceHttpEntries(observations, predicted)).toEqual([
      { method: "GET", path: "/api/market/prices", status: 200 },
      { method: "GET", path: "/api/market/prices", status: 200 },
    ]);
    // A page-side 429 therefore never reaches the ledger or the downgrade...
    expect(forbiddenStatusFailure(priceHttpEntries(observations, predicted))).toBeNull();
    // ...while a 429 on an attributed request does, and is kept as evidence.
    const attributed429 = [good(["B", "C"]), good(["D"], { status: 429 })];
    expect(priceHttpEntries(attributed429, predicted).map((entry) => entry.status)).toEqual([200, 429]);
    expect(forbiddenStatusFailure(priceHttpEntries(attributed429, predicted))).toBe("RATE_LIMITED");
  });

  it("records status 0 for an attributed request that failed or has no response yet", () => {
    const failed = good(["D"], { status: null, failed: true, settled: true });
    const pending = good(["B", "C"], { status: null, settled: false });
    expect(priceHttpEntries([pending, failed], predicted).map((entry) => entry.status)).toEqual([0, 0]);
  });

  it("never records ticker lists: entries carry only a method, a path template and a status", () => {
    const entries = priceHttpEntries([good(["B", "C"]), good(["D"])], predicted);
    expect(Object.keys(entries[0]).sort()).toEqual(["method", "path", "status"]);
    expect(JSON.stringify(entries)).not.toMatch(/tickers|"B"|"C"|"D"/);
  });
});
