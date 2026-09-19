// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  addOneToQuantity,
  buildExpectedDraft,
  chooseEditTicker,
  compareTickers,
  GOLDEN_SCHEMA,
  GoldenFileError,
  holdingPairsEqual,
  parseCatalog,
  parseGoldenFile,
  parsePortfolioList,
  parsePortfolioObject,
  parsePresence,
  parsePriceRows,
  parseRequestJson,
  parseSummaryFreshness,
  putBodyMatchesDraft,
  QuantityError,
  resetBodyMatches,
  sameTickerSet,
  sortHoldings,
  type GoldenState,
} from "../../../production-e2e/lib/response-shapes";

const USER = "00000000-0000-0000-0000-0000000d3110";
const SHA = "A".repeat(64);

const goldenJson = (overrides: Record<string, unknown> = {}) =>
  JSON.stringify({
    schema: GOLDEN_SCHEMA,
    catalogSha256: SHA,
    holdings: [
      { assetTicker: "AAPL", quantity: "31.00000000" },
      { assetTicker: "BTC-USD", quantity: "7.00000000" },
      { assetTicker: "MSFT", quantity: "50.00000000" },
    ],
    activeTickers: ["AAPL", "BTC-USD", "MSFT"],
    ...overrides,
  });

const golden: GoldenState = parseGoldenFile(goldenJson());

describe("Step B 5b portfolio list parsing", () => {
  const entry = (overrides: Record<string, unknown> = {}) => ({
    id: "p1",
    userId: USER,
    version: 5,
    holdings: [
      { id: "h1", assetTicker: "MSFT", quantity: "50.00000000" },
      { id: "h2", assetTicker: "AAPL", quantity: "31.00000000" },
    ],
    ...overrides,
  });

  it("selects the single matching entry even when another user's portfolio comes first, keeping wire order", () => {
    const result = parsePortfolioList([entry({ userId: "someone-else", version: 99 }), entry()], USER);
    expect(result).toEqual({
      ok: true,
      snapshot: {
        userId: USER,
        version: 5,
        holdings: [
          { ticker: "MSFT", quantity: "50.00000000" },
          { ticker: "AAPL", quantity: "31.00000000" },
        ],
      },
    });
  });

  it.each([
    ["an empty list", []],
    ["a list with no matching identity", [entry({ userId: "someone-else" })]],
    ["two matching entries", [entry(), entry({ id: "p2" })]],
  ])("rejects %s as an identity mismatch", (_label, body) => {
    expect(parsePortfolioList(body, USER)).toEqual({ ok: false, code: "IDENTITY_MISMATCH" });
  });

  it.each([
    ["a non-array body", { userId: USER }],
    ["null", null],
    ["a string", "[]"],
  ])("rejects %s", (_label, body) => {
    expect(parsePortfolioList(body, USER)).toEqual({ ok: false, code: "NOT_ARRAY" });
  });

  it.each([
    ["a missing version", entry({ version: undefined })],
    ["a negative version", entry({ version: -1 })],
    ["a fractional version", entry({ version: 1.5 })],
    ["a string version", entry({ version: "5" })],
    ["holdings that are not an array", entry({ holdings: {} })],
    ["a numeric quantity (fidelity lost)", entry({ holdings: [{ assetTicker: "AAPL", quantity: 31 }] })],
    ["an empty ticker", entry({ holdings: [{ assetTicker: "", quantity: "1.00000000" }] })],
    ["a missing ticker", entry({ holdings: [{ quantity: "1.00000000" }] })],
    [
      "duplicate tickers",
      entry({
        holdings: [
          { assetTicker: "AAPL", quantity: "1.00000000" },
          { assetTicker: "AAPL", quantity: "2.00000000" },
        ],
      }),
    ],
  ])("rejects %s as malformed", (_label, portfolio) => {
    expect(parsePortfolioList([portfolio], USER)).toEqual({ ok: false, code: "MALFORMED_PORTFOLIO" });
  });

  it("accepts version 0 and an empty holdings list", () => {
    expect(parsePortfolioList([entry({ version: 0, holdings: [] })], USER)).toEqual({
      ok: true,
      snapshot: { userId: USER, version: 0, holdings: [] },
    });
  });

  it("parses a single PortfolioResponse object with the same identity rule", () => {
    expect(parsePortfolioObject(entry(), USER).ok).toBe(true);
    expect(parsePortfolioObject(entry({ userId: "someone-else" }), USER)).toEqual({ ok: false, code: "IDENTITY_MISMATCH" });
    expect(parsePortfolioObject([entry()], USER)).toEqual({ ok: false, code: "NOT_OBJECT" });
    expect(parsePortfolioObject(null, USER)).toEqual({ ok: false, code: "NOT_OBJECT" });
  });
});

describe("Step B 5b holding comparisons", () => {
  it("compares sorted (ticker, quantity) pairs as strings, ignoring input order", () => {
    const a = [
      { ticker: "MSFT", quantity: "50.00000000" },
      { ticker: "AAPL", quantity: "31.00000000" },
    ];
    expect(holdingPairsEqual(a, sortHoldings(a))).toBe(true);
    expect(holdingPairsEqual(a, [...a].reverse())).toBe(true);
  });

  it("does not equate different string forms of the same number", () => {
    expect(
      holdingPairsEqual([{ ticker: "AAPL", quantity: "31.00000000" }], [{ ticker: "AAPL", quantity: "31" }]),
    ).toBe(false);
  });

  it("rejects a different length, ticker or quantity", () => {
    const one = [{ ticker: "AAPL", quantity: "1.00000000" }];
    expect(holdingPairsEqual(one, [])).toBe(false);
    expect(holdingPairsEqual(one, [{ ticker: "AAPM", quantity: "1.00000000" }])).toBe(false);
    expect(holdingPairsEqual(one, [{ ticker: "AAPL", quantity: "2.00000000" }])).toBe(false);
  });

  it("sorts tickers by code unit", () => {
    expect(compareTickers("A", "a")).toBe(-1);
    expect(compareTickers("a", "A")).toBe(1);
    expect(compareTickers("A", "A")).toBe(0);
    expect(sortHoldings([{ ticker: "b", quantity: "1" }, { ticker: "B", quantity: "1" }]).map((h) => h.ticker)).toEqual(["B", "b"]);
  });

  it("compares ticker sets without regard to order and rejects duplicates", () => {
    expect(sameTickerSet(["A", "B"], ["B", "A"])).toBe(true);
    expect(sameTickerSet(["A", "B"], ["A"])).toBe(false);
    expect(sameTickerSet(["A", "A"], ["A", "A"])).toBe(false);
    expect(sameTickerSet([], [])).toBe(true);
  });
});

describe("Step B 5b golden file", () => {
  it("parses the orchestrator's file", () => {
    expect(golden.catalogSha256).toBe(SHA);
    expect(golden.holdings).toHaveLength(3);
    expect(golden.activeTickers).toEqual(["AAPL", "BTC-USD", "MSFT"]);
  });

  const code = (text: string): string => {
    try {
      parseGoldenFile(text);
    } catch (error) {
      expect(error).toBeInstanceOf(GoldenFileError);
      return (error as GoldenFileError).code;
    }
    return "NO_ERROR";
  };

  it.each([
    ["non-JSON", "{", "GOLDEN_NOT_JSON"],
    ["an array", "[]", "GOLDEN_KEYS"],
    ["an unknown key", goldenJson({ generatedAt: "now" }), "GOLDEN_KEYS"],
    ["a wrong schema id", goldenJson({ schema: "wave10-5b-golden-v2" }), "GOLDEN_SCHEMA"],
    ["a short sha", goldenJson({ catalogSha256: "ABC" }), "GOLDEN_CATALOG_SHA"],
    ["no holdings", goldenJson({ holdings: [] }), "GOLDEN_HOLDINGS"],
    ["a holding with an extra key", goldenJson({ holdings: [{ assetTicker: "AAPL", quantity: "1.00000000", id: "x" }] }), "GOLDEN_HOLDING_SHAPE"],
    ["a quantity that is not eight decimals", goldenJson({ holdings: [{ assetTicker: "AAPL", quantity: "31.0" }] }), "GOLDEN_HOLDING_VALUE"],
    ["a numeric quantity", goldenJson({ holdings: [{ assetTicker: "AAPL", quantity: 31 }] }), "GOLDEN_HOLDING_VALUE"],
    [
      "holdings not sorted",
      goldenJson({
        holdings: [
          { assetTicker: "MSFT", quantity: "1.00000000" },
          { assetTicker: "AAPL", quantity: "1.00000000" },
        ],
      }),
      "GOLDEN_HOLDINGS_NOT_SORTED_UNIQUE",
    ],
    [
      "duplicate holdings",
      goldenJson({
        holdings: [
          { assetTicker: "AAPL", quantity: "1.00000000" },
          { assetTicker: "AAPL", quantity: "1.00000000" },
        ],
      }),
      "GOLDEN_HOLDINGS_NOT_SORTED_UNIQUE",
    ],
    ["empty activeTickers", goldenJson({ activeTickers: [] }), "GOLDEN_ACTIVE_TICKERS"],
    ["duplicate activeTickers", goldenJson({ activeTickers: ["A", "A"] }), "GOLDEN_ACTIVE_TICKERS"],
    ["a non-string active ticker", goldenJson({ activeTickers: ["A", 1] }), "GOLDEN_ACTIVE_TICKERS"],
  ])("rejects %s", (_label, text, expected) => {
    expect(code(text)).toBe(expected);
  });
});

describe("Step B 5b quantity arithmetic", () => {
  it.each([
    ["31.00000000", "32.00000000"],
    ["0.00000001", "1.00000001"],
    ["49.50000000", "50.50000000"],
    ["99999999998.99999999", "99999999999.99999999"],
    ["9.00000000", "10.00000000"],
  ])("adds exactly one to %s", (input, output) => {
    expect(addOneToQuantity(input)).toBe(output);
  });

  it.each(["31", "31.0", "31.000000000", "-1.00000000", "abc", "", "1e3", " 31.00000000", "123456789012.00000000"])(
    "rejects %j",
    (input) => {
      expect(() => addOneToQuantity(input)).toThrow(QuantityError);
    },
  );

  it("refuses to overflow the 11-digit integer domain", () => {
    expect(() => addOneToQuantity("99999999999.00000000")).toThrow(/QUANTITY_OVERFLOW/);
  });
});

describe("Step B 5b expected draft", () => {
  it("prefers AAPL, else the first golden ticker", () => {
    expect(chooseEditTicker(golden)).toBe("AAPL");
    const withoutAapl = parseGoldenFile(
      goldenJson({ holdings: [{ assetTicker: "BTC-USD", quantity: "7.00000000" }, { assetTicker: "MSFT", quantity: "50.00000000" }] }),
    );
    expect(chooseEditTicker(withoutAapl)).toBe("BTC-USD");
  });

  it("is golden with exactly one quantity replaced", () => {
    const draft = buildExpectedDraft(golden, "AAPL");
    expect(draft.fromQuantity).toBe("31.00000000");
    expect(draft.toQuantity).toBe("32.00000000");
    expect(draft.holdings).toEqual([
      { ticker: "AAPL", quantity: "32.00000000" },
      { ticker: "BTC-USD", quantity: "7.00000000" },
      { ticker: "MSFT", quantity: "50.00000000" },
    ]);
    expect(holdingPairsEqual(draft.holdings, golden.holdings)).toBe(false);
  });

  it("refuses a ticker golden does not hold", () => {
    expect(() => buildExpectedDraft(golden, "NOPE")).toThrow(QuantityError);
  });
});

describe("Step B 5b request bodies", () => {
  const expected = buildExpectedDraft(golden, "AAPL").holdings;
  const body = (overrides: Record<string, unknown> = {}) => ({
    expectedVersion: 5,
    holdings: expected.map((h) => ({ ticker: h.ticker, quantity: h.quantity })),
    ...overrides,
  });

  it("accepts the exact save body in any holding order", () => {
    expect(putBodyMatchesDraft(body(), expected)).toBe(true);
    expect(putBodyMatchesDraft(body({ holdings: [...body().holdings].reverse() }), expected)).toBe(true);
  });

  it.each([
    ["an extra top-level key", body({ extra: 1 })],
    ["a missing expectedVersion", body({ expectedVersion: undefined })],
    ["a string expectedVersion", body({ expectedVersion: "5" })],
    ["a wire-shaped holding (assetTicker)", body({ holdings: [{ assetTicker: "AAPL", quantity: "32.00000000" }] })],
    ["a numeric quantity", body({ holdings: [{ ticker: "AAPL", quantity: 32 }] })],
    ["the unedited quantity", body({ holdings: golden.holdings.map((h) => ({ ticker: h.ticker, quantity: h.quantity })) })],
    ["a missing holding", body({ holdings: body().holdings.slice(1) })],
    ["an extra key on a holding", body({ holdings: body().holdings.map((h) => ({ ...h, id: "x" })) })],
    ["duplicate holdings", body({ holdings: [body().holdings[0], body().holdings[0], body().holdings[1]] })],
    ["a null body", null],
    ["an array body", []],
  ])("rejects %s", (_label, candidate) => {
    expect(putBodyMatchesDraft(candidate, expected)).toBe(false);
  });

  it("matches the reset body only when it is exactly {expectedVersion}", () => {
    expect(resetBodyMatches({ expectedVersion: 6 }, 6)).toBe(true);
    expect(resetBodyMatches({ expectedVersion: 7 }, 6)).toBe(false);
    expect(resetBodyMatches({ expectedVersion: 6, extra: 1 }, 6)).toBe(false);
    expect(resetBodyMatches({}, 6)).toBe(false);
    expect(resetBodyMatches(null, 6)).toBe(false);
    expect(resetBodyMatches([6], 6)).toBe(false);
  });

  it("parses captured request text and treats absent or invalid text as undefined", () => {
    expect(parseRequestJson('{"a":1}')).toEqual({ a: 1 });
    expect(parseRequestJson(null)).toBeUndefined();
    expect(parseRequestJson("not json")).toBeUndefined();
  });
});

describe("Step B 5b summary, catalog, presence and price bodies", () => {
  it("reads the freshness state and validates the counts", () => {
    const summary = (freshness: unknown) => ({ userId: USER, assetPriceFreshness: freshness });
    const counts = { staleHoldings: 0, unknownPriceHoldings: 0, missingPriceHoldings: 0 };
    for (const state of ["FRESH", "STALE", "UNKNOWN", "MISSING"] as const) {
      expect(parseSummaryFreshness(summary({ state, ...counts }))).toEqual({ state, countsValid: true });
    }
    expect(parseSummaryFreshness(summary({ state: "STALE", ...counts, staleHoldings: 3 })).countsValid).toBe(true);
    expect(parseSummaryFreshness(summary({ state: "FRESH", ...counts, staleHoldings: -1 })).countsValid).toBe(false);
    expect(parseSummaryFreshness(summary({ state: "FRESH", ...counts, staleHoldings: 1.5 })).countsValid).toBe(false);
    expect(parseSummaryFreshness(summary({ state: "FRESH", ...counts, staleHoldings: "0" })).countsValid).toBe(false);
    expect(parseSummaryFreshness(summary({ state: "FRESH", staleHoldings: 0, unknownPriceHoldings: 0 })).countsValid).toBe(false);
  });

  it("reports ABSENT for a missing field, a null field or an unknown state", () => {
    const absent = { state: "ABSENT", countsValid: false };
    expect(parseSummaryFreshness({ userId: USER })).toEqual(absent);
    expect(parseSummaryFreshness({ assetPriceFreshness: null })).toEqual(absent);
    expect(parseSummaryFreshness(null)).toEqual(absent);
    expect(parseSummaryFreshness([])).toEqual(absent);
    expect(
      parseSummaryFreshness({
        assetPriceFreshness: { state: "ROTTEN", staleHoldings: 0, unknownPriceHoldings: 0, missingPriceHoldings: 0 },
      }),
    ).toEqual({ state: "ABSENT", countsValid: true });
  });

  it("parses the catalog: version, non-empty assets and the active ticker set", () => {
    const asset = (ticker: string, lifecycleStatus: string) => ({ ticker, name: ticker, lifecycleStatus });
    expect(
      parseCatalog({ catalogVersion: "v1", assets: [asset("AAPL", "ACTIVE"), asset("OLD", "DEPRECATED"), asset("MSFT", "ACTIVE")] }),
    ).toEqual({ assetsNonEmpty: true, activeTickers: ["AAPL", "MSFT"] });
    expect(parseCatalog({ catalogVersion: "", assets: [asset("A", "ACTIVE")] }).assetsNonEmpty).toBe(false);
    expect(parseCatalog({ assets: [asset("A", "ACTIVE")] }).assetsNonEmpty).toBe(false);
    expect(parseCatalog({ catalogVersion: "v1", assets: [] })).toEqual({ assetsNonEmpty: false, activeTickers: [] });
    expect(parseCatalog({ catalogVersion: "v1", assets: [{ ticker: "A" }] })).toEqual({ assetsNonEmpty: false, activeTickers: [] });
    expect(parseCatalog(null)).toEqual({ assetsNonEmpty: false, activeTickers: [] });
  });

  it("reads presence as a boolean or null", () => {
    expect(parsePresence({ anotherSessionActive: true })).toBe(true);
    expect(parsePresence({ anotherSessionActive: false })).toBe(false);
    expect(parsePresence({ anotherSessionActive: "false" })).toBeNull();
    expect(parsePresence({})).toBeNull();
    expect(parsePresence(null)).toBeNull();
  });

  it("recognises array-shaped price rows and a non-null numeric price", () => {
    expect(parsePriceRows([{ ticker: "A", currentPrice: 12.5 }, { ticker: "B", currentPrice: null }])).toEqual({
      arrayShaped: true,
      nonNullPriceSeen: true,
    });
    expect(parsePriceRows([{ ticker: "A", currentPrice: null, priceUnavailable: true }])).toEqual({
      arrayShaped: true,
      nonNullPriceSeen: false,
    });
    expect(parsePriceRows([{ ticker: "A", currentPrice: 5, priceUnavailable: true }]).nonNullPriceSeen).toBe(false);
    expect(parsePriceRows([{ ticker: "A", currentPrice: "5" }]).nonNullPriceSeen).toBe(false);
    expect(parsePriceRows([{ ticker: "A", currentPrice: Number.NaN }]).nonNullPriceSeen).toBe(false);
    expect(parsePriceRows([])).toEqual({ arrayShaped: true, nonNullPriceSeen: false });
    expect(parsePriceRows({ ticker: "A" })).toEqual({ arrayShaped: false, nonNullPriceSeen: false });
    expect(parsePriceRows([{ currentPrice: 1 }])).toEqual({ arrayShaped: false, nonNullPriceSeen: false });
    expect(parsePriceRows(null)).toEqual({ arrayShaped: false, nonNullPriceSeen: false });
  });
});
