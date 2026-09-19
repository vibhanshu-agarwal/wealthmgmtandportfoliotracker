// @vitest-environment node
import { describe, expect, it } from "vitest";
import contractJson from "../../../production-e2e/ledger-contract.json";
import {
  assertContractMatchesCode,
  LEDGER_PATHS,
  LEG_IDS,
  LedgerContractError,
  loadLedgerContract,
  parseLedgerContract,
  passHttpViolation,
  type LedgerContract,
  type LegId,
  type PassHttpRule,
} from "../../../production-e2e/lib/contract";
import {
  LedgerValidationError,
  LedgerWriter,
  validateSpecLedgerLine,
  type FactValue,
  type HttpEntry,
} from "../../../production-e2e/lib/ledger";
import {
  httpOf,
  ledgerStatusOf,
  PageRecorder,
  type RequestLike,
  type ResponseLike,
} from "../../../production-e2e/lib/page-recorder";
import {
  chooseAttributableTicker,
  evaluatePriceObservations,
  pageBatches,
  priceHttpEntries,
} from "../../../production-e2e/lib/price-attribution";

const contract: LedgerContract = loadLedgerContract();

const FRONTEND = "https://vibhanshu-ai-portfolio.dev";
const API = "https://api.vibhanshu-ai-portfolio.dev";
const USER = "00000000-0000-0000-0000-0000000d3110";

type Pair = { facts: Record<string, FactValue>; http: HttpEntry[] };

function memoryWriter() {
  const lines: string[] = [];
  const writer = new LedgerWriter({
    contract,
    sink: { append: (line) => void lines.push(line) },
    now: () => new Date("2026-09-19T12:34:56.789Z"),
  });
  return { writer, lines };
}

/** The error the writer raises for a leg written as passed, or null when it accepts it. */
function passedError(leg: LegId, pair: Pair): LedgerValidationError | null {
  const { writer, lines } = memoryWriter();
  try {
    writer.writeLeg({ leg, status: "passed", reason: "OK", http: pair.http, facts: pair.facts });
  } catch (error) {
    expect(error).toBeInstanceOf(LedgerValidationError);
    expect(lines).toHaveLength(0);
    expect(writer.hasWrittenLeg(leg)).toBe(false);
    return error as LedgerValidationError;
  }
  expect(lines).toHaveLength(1);
  return null;
}

const entry = (method: string, path: string, status: number): HttpEntry => ({ method, path, status });

// -- an honest run, driven through the real recorder the way step-b-5b.spec.ts drives it ---------------------------

function fakeRequest(method: string, url: string): RequestLike {
  const headers = { authorization: "Bearer SECRET-TOKEN-VALUE" };
  return {
    url: () => url,
    method: () => method,
    headers: () => headers,
    postData: () => null,
    allHeaders: async () => headers,
  };
}

function fakeResponse(request: RequestLike, status: number, body: unknown, headers: Record<string, string> = {}): ResponseLike {
  return { status: () => status, headers: () => headers, request: () => request, json: async () => body };
}

const portfolioBody = (version: number, tickers: string[]) => [
  {
    id: "p",
    userId: USER,
    version,
    holdings: tickers.map((ticker, index) => ({ id: `h${index}`, assetTicker: ticker, quantity: "1.00000000" })),
  },
];

function newRecorder(): PageRecorder {
  return new PageRecorder({ origins: { frontend: FRONTEND, api: API }, ledgerPaths: LEDGER_PATHS, userId: USER });
}

/** Sends one request through the recorder and answers it. */
async function exchange(
  recorder: PageRecorder,
  method: string,
  url: string,
  status: number,
  body: unknown,
  headers?: Record<string, string>,
): Promise<RequestLike> {
  const request = fakeRequest(method, url);
  recorder.onRequest(request);
  await recorder.onResponse(fakeResponse(request, status, body, headers));
  return request;
}

/**
 * The (facts, http) pair every leg reports on an honest passing run. Each leg's http and its count and status
 * facts are derived from the recorder with the SAME helpers, and in the same shape, as the corresponding
 * run* function in step-b-5b.spec.ts (which cannot be imported here: loading it registers a Playwright test).
 * A static guard in step-b-5b-config-and-static-guards.test.ts pins that the spec still calls them this way.
 */
async function honestPair(leg: LegId): Promise<Pair> {
  const recorder = newRecorder();
  const heldTickers = Array.from({ length: 60 }, (_, index) => `T${String(index).padStart(3, "0")}`);

  switch (leg) {
    case "L0":
      // runL0: check.report([], { ... })
      return {
        http: [],
        facts: { finalPathIsPortfolio: true, headingPortfolio: true, redirectedToLogin: false, unauthorized401Seen: false },
      };

    case "L1": {
      await exchange(recorder, "GET", `${API}/api/portfolio`, 200, portfolioBody(3, heldTickers));
      // runL1: both values from one synchronous read of the recorder.
      const portfolioReads = recorder.settledPortfolioReads();
      const portfolioLoadStatus = recorder.lastPortfolioStatus();
      return {
        http: httpOf(portfolioReads),
        facts: { editButtonVisible: true, resetButtonVisible: true, portfolioLoadStatus, resetEnabled: true },
      };
    }

    case "L2": {
      await exchange(recorder, "GET", `${API}/api/portfolio/summary`, 200, {
        assetPriceFreshness: { state: "FRESH", staleHoldings: 0, unknownPriceHoldings: 0, missingPriceHoldings: 0 },
      });
      return {
        http: httpOf(recorder.find("GET", "/api/portfolio/summary")),
        facts: { freshnessState: "FRESH", countsValid: true, stripVisible: true },
      };
    }

    case "L3":
      // runL3: check.report([], { ... })
      return { http: [], facts: { dialogOpen: true, unavailableNoticeVisible: false } };

    case "L4": {
      const openMark = recorder.mark();
      await exchange(
        recorder,
        "GET",
        `${API}/api/assets`,
        200,
        { catalogVersion: "v1", assets: [{ ticker: "AAPL", lifecycleStatus: "ACTIVE" }] },
        { etag: '"v1"' },
      );
      const catalogEntry = recorder.firstAfter(openMark, "GET", "/api/assets");
      return {
        http: httpOf(recorder.find("GET", "/api/assets", openMark)),
        facts: {
          catalogStatus: ledgerStatusOf(catalogEntry),
          noIfNoneMatch: true,
          etagPresent: true,
          assetsNonEmpty: true,
          rowsRendered: true,
          catalogParity: true,
          activeCount: 159,
        },
      };
    }

    case "L5": {
      const openMark = recorder.mark();
      await exchange(recorder, "GET", `${API}/api/presence/demo`, 200, { anotherSessionActive: false });
      const requests = recorder.find("GET", "/api/presence/demo", openMark);
      return {
        http: httpOf(requests),
        facts: {
          presenceRequestsSinceOpen: requests.length,
          presenceStatus: ledgerStatusOf(requests[0]),
          anotherSessionActive: false,
          requestFailed: false,
          corsConsoleError: false,
        },
      };
    }

    case "L6": {
      await exchange(recorder, "GET", `${API}/api/portfolio`, 200, portfolioBody(3, heldTickers));
      for (const batch of pageBatches(heldTickers)) {
        await exchange(recorder, "GET", `${API}/api/market/prices?tickers=${encodeURIComponent(batch.join(","))}`, 200, [
          { ticker: batch[0], currentPrice: 1 },
        ]);
      }
      const attribution = chooseAttributableTicker(heldTickers, recorder.portfolioWireOrders());
      expect(attribution).not.toBeNull();
      const predicted = attribution!.predicted;
      const mark = recorder.mark();
      for (const batch of predicted) {
        await exchange(recorder, "GET", `${API}/api/market/prices?tickers=${encodeURIComponent(batch.join(","))}`, 200, [
          { ticker: batch[0], currentPrice: 2 },
        ]);
      }
      // A page-side refetch after the uncheck that failed: not attributed to route 9.3, so never recorded.
      await exchange(recorder, "GET", `${API}/api/market/prices?tickers=PAGE-SIDE`, 500, null);
      const observations = recorder.priceObservationsAfter(mark);
      const evaluation = evaluatePriceObservations(observations, predicted);
      return {
        http: priceHttpEntries(observations, predicted),
        facts: {
          priceRequestsAfterUncheck: evaluation.priceRequestsAfterUncheck,
          allStatus200: evaluation.allStatus200,
          arrayShaped: evaluation.arrayShaped,
          nonNullPriceSeen: evaluation.nonNullPriceSeen,
          disjointFromPageBatches: true,
          predictedBatchCount: predicted.length,
        },
      };
    }

    case "L7":
      // runL7: check.report([], { ... })
      return { http: [], facts: { pageWriteRequestsBeforeMutation: 0 } };

    case "L8": {
      const saveMark = recorder.mark();
      await exchange(recorder, "PUT", `${API}/api/portfolio/holdings`, 200, portfolioBody(4, heldTickers)[0]);
      const puts = recorder.find("PUT", "/api/portfolio/holdings", saveMark);
      return {
        http: httpOf(puts),
        facts: {
          putCount: puts.length,
          putStatus: ledgerStatusOf(puts[0]),
          expectedVersionMatchesObserved: true,
          bodyMatchesExpectedDraft: true,
          versionAdvanced: true,
          savedStatusVisible: true,
          independentReadVersionMatches: true,
          independentReadHoldingsMatch: true,
          mutationSkippedReason: "NONE",
        },
      };
    }

    case "L9": {
      const mark = recorder.mark();
      await exchange(recorder, "PUT", `${API}/api/portfolio/demo-reset`, 200, portfolioBody(5, heldTickers)[0]);
      const resets = recorder.find("PUT", "/api/portfolio/demo-reset", mark);
      return {
        http: httpOf(resets),
        facts: {
          putCount: resets.length,
          putStatus: ledgerStatusOf(resets[0]),
          noInternalKeyHeader: true,
          expectedVersionMatchesSaved: true,
          versionPlusOne: true,
          responseEqualsGolden: true,
          resetStatusVisible: true,
          independentReadEqualsGolden: true,
          mutationSkippedReason: "NONE",
        },
      };
    }
  }
}

// -- the contract's table -------------------------------------------------------------------------------------------

const EXPECTED_PASS_HTTP = {
  L0: { entries: "none" },
  L1: { entries: { method: "GET", path: "/api/portfolio" }, statusFacts: { portfolioLoadStatus: "last" } },
  L2: { entries: { method: "GET", path: "/api/portfolio/summary" }, firstStatus: 200 },
  L3: { entries: "none" },
  L4: { entries: { method: "GET", path: "/api/assets" }, statusFacts: { catalogStatus: "first" } },
  L5: {
    entries: { method: "GET", path: "/api/presence/demo" },
    countFact: "presenceRequestsSinceOpen",
    statusFacts: { presenceStatus: "first" },
  },
  L6: { entries: { method: "GET", path: "/api/market/prices" }, countFact: "priceRequestsAfterUncheck", allStatus: 200 },
  L7: { entries: "none" },
  L8: {
    entries: { method: "PUT", path: "/api/portfolio/holdings" },
    countFact: "putCount",
    statusFacts: { putStatus: "first" },
  },
  L9: {
    entries: { method: "PUT", path: "/api/portfolio/demo-reset" },
    countFact: "putCount",
    statusFacts: { putStatus: "first" },
  },
};

describe("Step B 5b passHttp table pin", () => {
  it("is exactly the table the spec's report calls imply, so loosening a rule needs a deliberate test change", () => {
    expect(contractJson.passHttp).toEqual(EXPECTED_PASS_HTTP);
    expect(Object.keys(contractJson.passHttp)).toEqual([...LEG_IDS]);
  });

  it("states the rule forms in prose and parses into one typed rule per leg", () => {
    for (const word of ["entries", "countFact", "statusFacts", "firstStatus", "allStatus", "contract error"]) {
      expect(contract.passHttpRules).toContain(word);
    }
    expect(Object.keys(contract.passHttp)).toEqual([...LEG_IDS]);
    expect(contract.passHttp.L0).toEqual({ entries: { kind: "none" }, countFact: null, statusFacts: {}, firstStatus: null, allStatus: null });
    expect(contract.passHttp.L1).toEqual({
      entries: { kind: "requests", method: "GET", path: "/api/portfolio" },
      countFact: null,
      statusFacts: { portfolioLoadStatus: "last" },
      firstStatus: null,
      allStatus: null,
    });
    expect(contract.passHttp.L2.firstStatus).toBe(200);
    expect(contract.passHttp.L4.statusFacts).toEqual({ catalogStatus: "first" });
    expect(contract.passHttp.L5.countFact).toBe("presenceRequestsSinceOpen");
    expect(contract.passHttp.L6.allStatus).toBe(200);
    expect(contract.passHttp.L8.entries).toEqual({ kind: "requests", method: "PUT", path: "/api/portfolio/holdings" });
    expect(contract.passHttp.L9.entries).toEqual({ kind: "requests", method: "PUT", path: "/api/portfolio/demo-reset" });
  });

  it("names, for every count and status fact, an integer fact that the leg's pass table also constrains", () => {
    for (const leg of LEG_IDS) {
      const rule = contract.passHttp[leg];
      const named = [...(rule.countFact === null ? [] : [rule.countFact]), ...Object.keys(rule.statusFacts)];
      for (const fact of named) {
        expect(contract.legFacts[leg][fact], `${leg}.${fact}`).toBe("integer");
        expect(Object.keys(contract.passFacts[leg]), `${leg}.${fact} is also a pass fact`).toContain(fact);
      }
    }
  });
});

describe("Step B 5b contract loading of the passHttp table (fails closed)", () => {
  type HttpTable = Record<string, Record<string, unknown>>;
  const clone = (): Record<string, unknown> => JSON.parse(JSON.stringify(contractJson)) as Record<string, unknown>;

  function withPassHttp(mutate: (table: HttpTable) => void): Record<string, unknown> {
    const copy = clone();
    mutate(copy.passHttp as HttpTable);
    return copy;
  }

  function withTopLevel(key: string, value: unknown): Record<string, unknown> {
    const copy = clone();
    copy[key] = value;
    return copy;
  }

  /** Sets an OWN enumerable property, even one named like an Object.prototype member. */
  function setOwn(target: object, key: string, value: unknown): void {
    Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true });
  }

  it("loads the shipped table", () => {
    expect(() => loadLedgerContract(clone())).not.toThrow();
  });

  it.each<[string, () => Record<string, unknown>]>([
    ["passHttp that is null", () => withTopLevel("passHttp", null)],
    ["passHttp that is an array", () => withTopLevel("passHttp", [])],
    ["passHttp that is a string", () => withTopLevel("passHttp", "L0")],
    [
      "a missing passHttp key",
      () => {
        const copy = clone();
        delete copy.passHttp;
        return copy;
      },
    ],
    [
      "a missing passHttpRules key",
      () => {
        const copy = clone();
        delete copy.passHttpRules;
        return copy;
      },
    ],
    ["passHttpRules that is not a string", () => withTopLevel("passHttpRules", ["rules"])],
    ["passHttpRules that is blank", () => withTopLevel("passHttpRules", "  ")],
  ])("rejects %s", (_label, build) => {
    expect(() => parseLedgerContract(build())).toThrow(LedgerContractError);
  });

  it.each<[string, (table: HttpTable) => void]>([
    // an unknown leg
    ["a table for an unknown leg", (t) => { t.L10 = { entries: "none" }; }],
    ["a table for an inherited property name", (t) => { setOwn(t, "constructor", { entries: "none" }); }],
    // a missing leg
    ["a missing leg (the last)", (t) => { delete t.L9; }],
    ["a missing leg (the first)", (t) => { delete t.L0; }],
    // a leg rule that is not an object
    ["the string shorthand instead of a rule object", (t) => { setOwn(t, "L3", "none"); }],
    ["a leg rule that is null", (t) => { setOwn(t, "L3", null); }],
    ["a leg rule that is an array", (t) => { setOwn(t, "L3", []); }],
    // an unknown table key, or no entries at all
    ["an unknown table key", (t) => { t.L1.extra = 1; }],
    ["a misspelled table key", (t) => { t.L5.countFacts = "presenceRequestsSinceOpen"; }],
    ["a leg rule without entries", (t) => { delete t.L1.entries; }],
    ["an empty leg rule", (t) => { t.L1 = {}; }],
    // entries neither "none" nor {method, path}
    ["entries that is another string", (t) => { t.L1.entries = "all"; }],
    ["entries that is 'None' in another case", (t) => { t.L0.entries = "None"; }],
    ["entries that is null", (t) => { t.L1.entries = null; }],
    ["entries that is an array", (t) => { t.L1.entries = [{ method: "GET", path: "/api/portfolio" }]; }],
    ["entries that is a number", (t) => { t.L1.entries = 5; }],
    ["entries that is an empty object", (t) => { t.L1.entries = {}; }],
    ["entries without a path", (t) => { t.L1.entries = { method: "GET" }; }],
    ["entries without a method", (t) => { t.L1.entries = { path: "/api/portfolio" }; }],
    ["entries with an extra key", (t) => { t.L1.entries = { method: "GET", path: "/api/portfolio", status: 200 }; }],
    // a method or a path outside the vocabulary
    ["a method outside the vocabulary", (t) => { t.L8.entries = { method: "POST", path: "/api/portfolio/holdings" }; }],
    ["a lower-case method", (t) => { t.L1.entries = { method: "get", path: "/api/portfolio" }; }],
    ["a method that is not a string", (t) => { t.L1.entries = { method: 5, path: "/api/portfolio" }; }],
    ["a path outside the vocabulary", (t) => { t.L1.entries = { method: "GET", path: "/api/nope" }; }],
    ["a path with a query string", (t) => { t.L4.entries = { method: "GET", path: "/api/assets?x=1" }; }],
    ["a full url instead of a path template", (t) => { t.L4.entries = { method: "GET", path: `${API}/api/assets` }; }],
    ["a path that is not a string", (t) => { t.L1.entries = { method: "GET", path: null }; }],
    // countFact naming a fact that is not an integer fact of the leg
    ["countFact naming an unknown fact", (t) => { t.L5.countFact = "nope"; }],
    ["countFact naming a boolean fact", (t) => { t.L5.countFact = "requestFailed"; }],
    ["countFact naming a boolean-or-null fact", (t) => { t.L5.countFact = "anotherSessionActive"; }],
    ["countFact naming an enum fact", (t) => { t.L8.countFact = "mutationSkippedReason"; }],
    ["countFact naming a fact of another leg", (t) => { t.L8.countFact = "presenceRequestsSinceOpen"; }],
    ["countFact naming an inherited property", (t) => { t.L8.countFact = "constructor"; }],
    ["countFact that is not a string", (t) => { t.L8.countFact = 7; }],
    ["countFact that is null", (t) => { t.L8.countFact = null; }],
    // statusFacts
    ["statusFacts that is null", (t) => { t.L4.statusFacts = null; }],
    ["statusFacts that is an array", (t) => { t.L4.statusFacts = ["catalogStatus"]; }],
    ["statusFacts naming an unknown fact", (t) => { t.L4.statusFacts = { nope: "first" }; }],
    ["statusFacts naming a boolean fact", (t) => { t.L4.statusFacts = { etagPresent: "first" }; }],
    ["statusFacts naming a fact of another leg", (t) => { t.L4.statusFacts = { putStatus: "first" }; }],
    ["statusFacts naming an inherited property", (t) => { setOwn(t.L4.statusFacts as object, "toString", "first"); }],
    ["a statusFacts position that is not first or last", (t) => { t.L4.statusFacts = { catalogStatus: "middle" }; }],
    ["a statusFacts position in another case", (t) => { t.L4.statusFacts = { catalogStatus: "First" }; }],
    ["a statusFacts position that is a number", (t) => { t.L4.statusFacts = { catalogStatus: 0 }; }],
    ["a statusFacts position that is null", (t) => { t.L4.statusFacts = { catalogStatus: null }; }],
    ["a statusFacts position that is a boolean", (t) => { t.L4.statusFacts = { catalogStatus: true }; }],
    // statusFacts present but naming nothing (the orchestrator refuses it too: "when present, must be non-empty")
    ["an empty statusFacts on a leg that states a count and a status fact", (t) => { t.L8.statusFacts = {}; }],
    ["an empty statusFacts on a leg that states only a firstStatus", (t) => { t.L2.statusFacts = {}; }],
    ["an empty statusFacts on a leg whose only other rule is its entries", (t) => { t.L4.statusFacts = {}; }],
    ["a statusFacts that lost its only fact", (t) => { delete (t.L5.statusFacts as Record<string, unknown>).presenceStatus; }],
    // firstStatus / allStatus not an integer http status
    ["a fractional firstStatus", (t) => { t.L2.firstStatus = 200.5; }],
    ["a string firstStatus", (t) => { t.L2.firstStatus = "200"; }],
    ["a boolean firstStatus", (t) => { t.L2.firstStatus = true; }],
    ["a null firstStatus", (t) => { t.L2.firstStatus = null; }],
    ["an array firstStatus", (t) => { t.L2.firstStatus = [200]; }],
    ["a firstStatus of zero, which no response carries", (t) => { t.L2.firstStatus = 0; }],
    ["a firstStatus below 100", (t) => { t.L2.firstStatus = 99; }],
    ["a firstStatus above 599", (t) => { t.L2.firstStatus = 600; }],
    ["a negative firstStatus", (t) => { t.L2.firstStatus = -200; }],
    ["a fractional allStatus", (t) => { t.L6.allStatus = 200.5; }],
    ["a string allStatus", (t) => { t.L6.allStatus = "200"; }],
    ["a null allStatus", (t) => { t.L6.allStatus = null; }],
    ["an allStatus above 599", (t) => { t.L6.allStatus = 1_000_000_000; }],
    // ...the same bounds the orchestrator enforces: an integer in 100..599, never 0, never past the safe range
    ["a firstStatus of 700", (t) => { t.L2.firstStatus = 700; }],
    ["a firstStatus beyond the safe integer range", (t) => { t.L2.firstStatus = 2 ** 53; }],
    ["an allStatus of zero", (t) => { t.L6.allStatus = 0; }],
    ["a negative allStatus", (t) => { t.L6.allStatus = -1; }],
    ["an allStatus of 10**12", (t) => { t.L6.allStatus = 10 ** 12; }],
    // "none" is the whole rule
    ["none entries carrying a countFact", (t) => { t.L0.countFact = "presenceRequestsSinceOpen"; }],
    ["none entries carrying an empty statusFacts", (t) => { t.L3.statusFacts = {}; }],
    ["none entries carrying a firstStatus", (t) => { t.L7.firstStatus = 200; }],
    ["none entries carrying an allStatus", (t) => { t.L7.allStatus = 200; }],
    ["none entries carrying a countFact that names a real integer fact of the leg", (t) => { t.L7.countFact = "pageWriteRequestsBeforeMutation"; }],
    ["none entries carrying a non-empty statusFacts", (t) => { t.L7.statusFacts = { pageWriteRequestsBeforeMutation: "first" }; }],
  ])("rejects %s", (_label, mutate) => {
    expect(() => parseLedgerContract(withPassHttp(mutate))).toThrow(LedgerContractError);
  });

  it("names the offending leg, key or part in the error, and never echoes a value", () => {
    const message = (mutate: (table: HttpTable) => void): string => {
      try {
        parseLedgerContract(withPassHttp(mutate));
      } catch (error) {
        return (error as Error).message;
      }
      return "";
    };
    expect(message((t) => { t.L1.entries = { method: "LEAKY-METHOD", path: "/api/portfolio" }; })).toContain("passHttp.L1.entries");
    expect(message((t) => { t.L1.entries = { method: "LEAKY-METHOD", path: "/api/portfolio" }; })).not.toContain("LEAKY-METHOD");
    expect(message((t) => { t.L4.entries = { method: "GET", path: "/LEAKY/path" }; })).not.toContain("LEAKY");
    expect(message((t) => { t.L4.statusFacts = { catalogStatus: "LEAKY-POSITION" }; })).not.toContain("LEAKY");
    expect(message((t) => { t.L2.firstStatus = "LEAKY-STATUS"; })).not.toContain("LEAKY");
    expect(message((t) => { t.L10 = { entries: "none" }; })).toContain("passHttp.L10");
    expect(message((t) => { delete t.L9; })).toContain("L9");
  });

  it("accepts a table that only uses the documented forms, including a leg that states no status rule", () => {
    const relaxed = withPassHttp((t) => {
      t.L1 = { entries: { method: "GET", path: "/api/portfolio" } };
      t.L6 = { entries: { method: "GET", path: "/api/market/prices" }, statusFacts: { priceRequestsAfterUncheck: "last" } };
    });
    expect(() => loadLedgerContract(relaxed)).not.toThrow();
  });

  // The positive pair of the rejections above: edits the orchestrator accepts too, so a reader that refused one of
  // them would fail a contract the other side loads.
  it.each<[string, (table: HttpTable) => void]>([
    ["two status facts, one read from the first entry and one from the last", (t) => { (t.L8.statusFacts as Record<string, string>).putCount = "last"; }],
    ["an allStatus of 200 added to a PUT leg", (t) => { t.L8.allStatus = 200; }],
    ["a firstStatus of 200 added to a PUT leg", (t) => { t.L8.firstStatus = 200; }],
    ["a last-position status fact added to the price leg", (t) => { t.L6.statusFacts = { predictedBatchCount: "last" }; }],
    ["another path template on the portfolio leg", (t) => { t.L1.entries = { method: "GET", path: "/api/portfolio/summary" }; }],
    ["a status-checking leg reduced to none entries alone", (t) => { t.L2 = { entries: "none" }; }],
    ["the boundary statuses 100 and 599", (t) => { t.L2.firstStatus = 100; t.L6.allStatus = 599; }],
  ])("accepts %s", (_label, mutate) => {
    expect(() => loadLedgerContract(withPassHttp(mutate))).not.toThrow();
  });

  it("names the leg whose statusFacts is empty and says why", () => {
    let message = "";
    try {
      parseLedgerContract(withPassHttp((t) => { t.L8.statusFacts = {}; }));
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain("passHttp.L8.statusFacts");
    expect(message).toContain("empty");
  });

  it("refuses to load a contract that describes the legs but leaves one without an http table, and says which", () => {
    expect(() => loadLedgerContract(withPassHttp((t) => { delete t.L5; }))).toThrow("passHttp is missing L5");
    expect(() => parseLedgerContract(withPassHttp((t) => { delete t.L0; }))).toThrow("passHttp is missing L0");
    expect(() => parseLedgerContract(withPassHttp((t) => { delete t.L9; }))).toThrow("passHttp is missing L9");
  });

  it("checks the leg set again on a contract object that did not come through the parser", () => {
    expect(() => assertContractMatchesCode(contract)).not.toThrow();
    const others = Object.fromEntries(Object.entries(contract.passHttp).filter(([leg]) => leg !== "L4"));
    expect(() => assertContractMatchesCode({ ...contract, passHttp: others })).toThrow(/passHttp must describe exactly/);
    expect(() => assertContractMatchesCode({ ...contract, passHttp: { ...contract.passHttp, L10: contract.passHttp.L0 } })).toThrow(
      /passHttp must describe exactly/,
    );
  });

  it("never believes a passed leg the contract has no http table for, even if the loader's own checks are bypassed", async () => {
    const others = Object.fromEntries(Object.entries(contract.passHttp).filter(([leg]) => leg !== "L4"));
    const withoutL4: LedgerContract = { ...contract, passHttp: others };
    const writer = new LedgerWriter({ contract: withoutL4, sink: { append: () => undefined } });
    const pair = await honestPair("L4");
    expect(() =>
      writer.writeLeg({ leg: "L4", status: "passed", reason: "OK", http: pair.http, facts: pair.facts }),
    ).toThrow(/HTTP_CONTRADICT_PASS/);
    // Legs that do have a table are unaffected, and an unconstrained failed L4 is still recorded.
    const l5 = await honestPair("L5");
    expect(() => writer.writeLeg({ leg: "L5", status: "passed", reason: "OK", http: l5.http, facts: l5.facts })).not.toThrow();
    expect(() =>
      writer.writeLeg({ leg: "L4", status: "failed", reason: "ASSERTION_FAILED", http: [], facts: {} }),
    ).not.toThrow();
  });
});

describe("Step B 5b passHttpViolation (the rule evaluator)", () => {
  const rule = (overrides: Partial<PassHttpRule> = {}): PassHttpRule => ({
    entries: { kind: "requests", method: "PUT", path: "/api/portfolio/holdings" },
    countFact: null,
    statusFacts: {},
    firstStatus: null,
    allStatus: null,
    ...overrides,
  });
  const put = (status: number) => entry("PUT", "/api/portfolio/holdings", status);

  it("none: the list must be empty", () => {
    const none = rule({ entries: { kind: "none" } });
    expect(passHttpViolation(none, [], {})).toBeNull();
    expect(passHttpViolation(none, [put(200)], {})).toBe("entries");
  });

  it("requests: the list must be non-empty and every entry must have the method and the path", () => {
    expect(passHttpViolation(rule(), [put(200)], {})).toBeNull();
    expect(passHttpViolation(rule(), [put(200), put(500)], {})).toBeNull();
    expect(passHttpViolation(rule(), [], {})).toBe("entries");
    expect(passHttpViolation(rule(), [entry("GET", "/api/portfolio/holdings", 200)], {})).toBe("entries");
    expect(passHttpViolation(rule(), [entry("PUT", "/api/portfolio/demo-reset", 200)], {})).toBe("entries");
    expect(passHttpViolation(rule(), [put(200), entry("PUT", "/api/portfolio/demo-reset", 200)], {})).toBe("entries");
    expect(passHttpViolation(rule(), [entry("PUT", "/api/portfolio/demo-reset", 200), put(200)], {})).toBe("entries");
  });

  it("countFact: the fact equals the number of entries, strictly", () => {
    const counted = rule({ countFact: "putCount" });
    expect(passHttpViolation(counted, [put(200)], { putCount: 1 })).toBeNull();
    expect(passHttpViolation(counted, [put(200), put(200)], { putCount: 1 })).toBe("count:putCount");
    expect(passHttpViolation(counted, [put(200)], { putCount: 2 })).toBe("count:putCount");
    expect(passHttpViolation(counted, [put(200)], { putCount: "1" })).toBe("count:putCount");
    expect(passHttpViolation(counted, [put(200)], { putCount: true })).toBe("count:putCount");
    expect(passHttpViolation(counted, [put(200)], {})).toBe("count:putCount");
    expect(passHttpViolation(counted, [put(200)], Object.create({ putCount: 1 }) as Record<string, unknown>)).toBe("count:putCount");
  });

  it("statusFacts: the fact equals the status of the first or of the last entry, strictly", () => {
    const first = rule({ statusFacts: { putStatus: "first" } });
    const last = rule({ statusFacts: { putStatus: "last" } });
    expect(passHttpViolation(first, [put(200), put(500)], { putStatus: 200 })).toBeNull();
    expect(passHttpViolation(first, [put(500), put(200)], { putStatus: 200 })).toBe("status:putStatus");
    expect(passHttpViolation(last, [put(500), put(200)], { putStatus: 200 })).toBeNull();
    expect(passHttpViolation(last, [put(200), put(500)], { putStatus: 200 })).toBe("status:putStatus");
    expect(passHttpViolation(first, [put(500)], { putStatus: 200 })).toBe("status:putStatus");
    expect(passHttpViolation(first, [put(200)], { putStatus: "200" })).toBe("status:putStatus");
    expect(passHttpViolation(first, [put(200)], {})).toBe("status:putStatus");
    // With no entry at all the entries rule is the first thing that fails.
    expect(passHttpViolation(first, [], { putStatus: 200 })).toBe("entries");
  });

  it("firstStatus: the first entry's status; allStatus: every entry's status", () => {
    expect(passHttpViolation(rule({ firstStatus: 200 }), [put(200), put(500)], {})).toBeNull();
    expect(passHttpViolation(rule({ firstStatus: 200 }), [put(500), put(200)], {})).toBe("firstStatus");
    expect(passHttpViolation(rule({ firstStatus: 200 }), [], {})).toBe("entries");
    expect(passHttpViolation(rule({ allStatus: 200 }), [put(200), put(200)], {})).toBeNull();
    expect(passHttpViolation(rule({ allStatus: 200 }), [put(200), put(500), put(200)], {})).toBe("allStatus");
    expect(passHttpViolation(rule({ allStatus: 200 }), [put(0)], {})).toBe("allStatus");
  });

  it("reports the first failing part, in the order entries, count, status, firstStatus, allStatus", () => {
    const all = rule({ countFact: "putCount", statusFacts: { putStatus: "first" }, firstStatus: 200, allStatus: 200 });
    const facts = { putCount: 1, putStatus: 200 };
    expect(passHttpViolation(all, [put(200)], facts)).toBeNull();
    expect(passHttpViolation(all, [], facts)).toBe("entries");
    expect(passHttpViolation(all, [put(200), put(200)], facts)).toBe("count:putCount");
    expect(passHttpViolation(all, [put(500)], facts)).toBe("status:putStatus");
    expect(passHttpViolation(rule({ firstStatus: 200, allStatus: 200 }), [put(500)], {})).toBe("firstStatus");
    expect(passHttpViolation(rule({ firstStatus: 200, allStatus: 200 }), [put(200), put(500)], {})).toBe("allStatus");
  });
});

describe("Step B 5b honest run: what each leg reports satisfies the pass tables", () => {
  it.each([...LEG_IDS])("%s: the pair an honest run reports is accepted by the writer as a passed leg", async (leg) => {
    const pair = await honestPair(leg);
    expect(passedError(leg, pair)).toBeNull();
  });

  it("L0, L3 and L7 report no http, L1 reports the settled portfolio reads, and the count legs report their counts", async () => {
    for (const leg of ["L0", "L3", "L7"] as const) expect((await honestPair(leg)).http).toEqual([]);
    expect((await honestPair("L1")).http).toEqual([entry("GET", "/api/portfolio", 200)]);
    expect((await honestPair("L2")).http).toEqual([entry("GET", "/api/portfolio/summary", 200)]);
    expect((await honestPair("L4")).http).toEqual([entry("GET", "/api/assets", 200)]);
    expect((await honestPair("L5")).http).toEqual([entry("GET", "/api/presence/demo", 200)]);
    expect((await honestPair("L8")).http).toEqual([entry("PUT", "/api/portfolio/holdings", 200)]);
    expect((await honestPair("L9")).http).toEqual([entry("PUT", "/api/portfolio/demo-reset", 200)]);
    const l6 = await honestPair("L6");
    expect(l6.http).toHaveLength(3);
    expect(l6.http.every((item) => item.method === "GET" && item.path === "/api/market/prices" && item.status === 200)).toBe(true);
    expect(l6.facts.priceRequestsAfterUncheck).toBe(3);
    expect(l6.facts.predictedBatchCount).toBe(3);
  });

  it("L6's recorded price requests are the attributed ones only: a failed page-side refetch is not recorded", async () => {
    const l6 = await honestPair("L6");
    expect(l6.http.map((item) => item.status)).toEqual([200, 200, 200]);
  });

  it("honest variants still pass: a retried portfolio read, a later summary or catalog attempt, a trailing in-flight refetch", async () => {
    // L1: a transient failure then the load that produced the controls, and a refetch still in flight afterwards.
    const l1 = newRecorder();
    await exchange(l1, "GET", `${API}/api/portfolio`, 500, null);
    await exchange(l1, "GET", `${API}/api/portfolio`, 200, portfolioBody(3, ["A"]));
    l1.onRequest(fakeRequest("GET", `${API}/api/portfolio`));
    const l1Reads = l1.settledPortfolioReads();
    const l1Pair: Pair = {
      http: httpOf(l1Reads),
      facts: { editButtonVisible: true, resetButtonVisible: true, portfolioLoadStatus: l1.lastPortfolioStatus(), resetEnabled: true },
    };
    expect(l1Pair.http.map((item) => item.status)).toEqual([500, 200]);
    expect(passedError("L1", l1Pair)).toBeNull();

    // L2 and L4 judge the first attempt; later attempts are only recorded.
    const l2 = await honestPair("L2");
    expect(passedError("L2", { ...l2, http: [...l2.http, entry("GET", "/api/portfolio/summary", 500)] })).toBeNull();
    const l4 = await honestPair("L4");
    expect(passedError("L4", { ...l4, http: [...l4.http, entry("GET", "/api/assets", 304)] })).toBeNull();
  });
});

describe("Step B 5b writer rejects a passed leg whose http contradicts its facts", () => {
  it("rejects the four shapes the false-GO probes demonstrated, with HTTP_CONTRADICT_PASS", async () => {
    const l8 = await honestPair("L8");
    const cases: Array<[string, LegId, Pair, string]> = [
      // L8 passed with http PUT 500 and putStatus 200
      ["a PUT that answered 500 beside putStatus 200", "L8", { ...l8, http: [entry("PUT", "/api/portfolio/holdings", 500)] }, "status:putStatus"],
      // http [] with putCount 1
      ["no PUT recorded beside putCount 1", "L8", { ...l8, http: [] }, "entries"],
      // two PUT 200 with putCount 1
      ["two PUTs beside putCount 1", "L8", { ...l8, http: [entry("PUT", "/api/portfolio/holdings", 200), entry("PUT", "/api/portfolio/holdings", 200)] }, "count:putCount"],
      // http on the demo-reset path for L8
      ["the demo-reset path for the save leg", "L8", { ...l8, http: [entry("PUT", "/api/portfolio/demo-reset", 200)] }, "entries"],
    ];
    for (const [label, leg, pair, detail] of cases) {
      const error = passedError(leg, pair);
      expect(error, label).toBeInstanceOf(LedgerValidationError);
      expect(error?.code, label).toBe("HTTP_CONTRADICT_PASS");
      expect(error?.message, label).toBe(`HTTP_CONTRADICT_PASS: ${detail}`);
    }
  });

  it("rejects the other demonstrated probes: doubled presence, a 500 catalog, a 500 among prices, http on the wrong reset path, a 500 summary", async () => {
    const l2 = await honestPair("L2");
    const l4 = await honestPair("L4");
    const l5 = await honestPair("L5");
    const l6 = await honestPair("L6");
    const l9 = await honestPair("L9");
    const cases: Array<[string, LegId, Pair, string]> = [
      ["two presence entries beside presenceRequestsSinceOpen 1", "L5", { ...l5, http: [...l5.http, ...l5.http] }, "count:presenceRequestsSinceOpen"],
      ["a 500 catalog beside catalogStatus 200", "L4", { ...l4, http: [entry("GET", "/api/assets", 500)] }, "status:catalogStatus"],
      [
        "three price entries including a 500 beside priceRequestsAfterUncheck 1 and allStatus200",
        "L6",
        { facts: { ...l6.facts, priceRequestsAfterUncheck: 1, predictedBatchCount: 1 }, http: [entry("GET", "/api/market/prices", 200), entry("GET", "/api/market/prices", 500), entry("GET", "/api/market/prices", 200)] },
        "count:priceRequestsAfterUncheck",
      ],
      ["a 500 among as many price entries as the count says", "L6", { ...l6, http: [l6.http[0], entry("GET", "/api/market/prices", 500), l6.http[2]] }, "allStatus"],
      ["http on the holdings path for the reset leg", "L9", { ...l9, http: [entry("PUT", "/api/portfolio/holdings", 200)] }, "entries"],
      ["a 500 summary", "L2", { ...l2, http: [entry("GET", "/api/portfolio/summary", 500)] }, "firstStatus"],
    ];
    for (const [label, leg, pair, detail] of cases) {
      const error = passedError(leg, pair);
      expect(error?.message, label).toBe(`HTTP_CONTRADICT_PASS: ${detail}`);
    }
  });

  it("rejects the same shapes through validateSpecLedgerLine, the function the writer calls", async () => {
    const l8 = await honestPair("L8");
    const line = (http: HttpEntry[]) => ({
      seq: 1,
      tUtc: "2026-09-19T12:34:56.789Z",
      src: "spec",
      event: "leg",
      leg: "L8",
      status: "passed",
      reason: "OK",
      http,
      facts: l8.facts,
    });
    expect(() => validateSpecLedgerLine(contract, line(l8.http))).not.toThrow();
    for (const http of [[], [entry("PUT", "/api/portfolio/holdings", 500)], [...l8.http, ...l8.http], [entry("PUT", "/api/portfolio/demo-reset", 200)]]) {
      expect(() => validateSpecLedgerLine(contract, line(http))).toThrow(/HTTP_CONTRADICT_PASS/);
    }
  });

  it("checks facts first: a passed leg with contradictory facts is FACTS_CONTRADICT_PASS even when its http is also wrong", async () => {
    const l8 = await honestPair("L8");
    const error = passedError("L8", { facts: { ...l8.facts, putStatus: 500 }, http: [] });
    expect(error?.code).toBe("FACTS_CONTRADICT_PASS");
  });

  it("does not constrain a failed or skipped leg: its http may say anything the vocabulary allows", async () => {
    const l8 = await honestPair("L8");
    const contradicting: Pair = { facts: l8.facts, http: [entry("PUT", "/api/portfolio/holdings", 500), entry("PUT", "/api/portfolio/holdings", 500)] };
    const failed = memoryWriter();
    expect(() =>
      failed.writer.writeLeg({ leg: "L8", status: "failed", reason: "HTTP_STATUS_NOT_200", http: contradicting.http, facts: contradicting.facts }),
    ).not.toThrow();
    const skipped = memoryWriter();
    expect(() =>
      skipped.writer.writeLeg({ leg: "L8", status: "skipped", reason: "SKIPPED_PRIOR_FAILURE", http: contradicting.http, facts: { mutationSkippedReason: "LEG_FAILED" } }),
    ).not.toThrow();
  });

  it("carries only a label and a fact name in the error, never a status or a value", async () => {
    const l8 = await honestPair("L8");
    const error = passedError("L8", { ...l8, http: [entry("PUT", "/api/portfolio/holdings", 599)] });
    expect(error?.message).toBe("HTTP_CONTRADICT_PASS: status:putStatus");
    expect(error?.message).not.toContain("599");
  });

  it("keeps the position semantics of the table: L1 judges the LAST read, L4 the FIRST catalog response, L2 the first summary", async () => {
    const l1 = await honestPair("L1");
    const read = (status: number) => entry("GET", "/api/portfolio", status);
    expect(passedError("L1", { ...l1, http: [read(500), read(200)] })).toBeNull();
    expect(passedError("L1", { ...l1, http: [read(200), read(500)] })?.message).toBe("HTTP_CONTRADICT_PASS: status:portfolioLoadStatus");

    const l4 = await honestPair("L4");
    const catalog = (status: number) => entry("GET", "/api/assets", status);
    expect(passedError("L4", { ...l4, http: [catalog(200), catalog(304)] })).toBeNull();
    expect(passedError("L4", { ...l4, http: [catalog(500), catalog(200)] })?.message).toBe("HTTP_CONTRADICT_PASS: status:catalogStatus");

    const l2 = await honestPair("L2");
    const summary = (status: number) => entry("GET", "/api/portfolio/summary", status);
    expect(passedError("L2", { ...l2, http: [summary(200), summary(500)] })).toBeNull();
    expect(passedError("L2", { ...l2, http: [summary(500), summary(200)] })?.message).toBe("HTTP_CONTRADICT_PASS: firstStatus");
  });
});

// -- every rule of every leg has a violating case ---------------------------------------------------------------------

interface Aspect {
  readonly leg: LegId;
  readonly label: string;
  /** The label the writer must name: fixed per aspect, except where the honest pair's size decides which part breaks first. */
  readonly detail: (honest: Pair) => string;
  /** The violating pair, derived from the honest one by breaking exactly this aspect of the leg's rule. */
  readonly violate: (honest: Pair) => Pair;
}

const otherPath = (path: string): string => LEDGER_PATHS.find((candidate) => candidate !== path) as string;
const otherMethod = (method: string): string => (method === "GET" ? "PUT" : "GET");

function aspectsOf(leg: LegId): Aspect[] {
  const rule = contract.passHttp[leg];
  const aspects: Aspect[] = [];

  if (rule.entries.kind === "none") {
    aspects.push({
      leg,
      label: "an entry where none is allowed",
      detail: () => "entries",
      violate: (honest) => ({ ...honest, http: [entry("GET", "/api/portfolio", 200)] }),
    });
  } else {
    const { method, path } = rule.entries;
    const good = (status = 200) => entry(method, path, status);
    aspects.push(
      { leg, label: "no entry at all", detail: () => "entries", violate: (honest) => ({ ...honest, http: [] }) },
      {
        leg,
        label: "every entry on the wrong method",
        detail: () => "entries",
        violate: (honest) => ({ ...honest, http: honest.http.map((item) => ({ ...item, method: otherMethod(method) })) }),
      },
      {
        leg,
        label: "a wrong-method entry after the good ones",
        detail: () => "entries",
        violate: (honest) => ({ ...honest, http: [...honest.http, entry(otherMethod(method), path, 200)] }),
      },
      {
        leg,
        label: "every entry on the wrong path",
        detail: () => "entries",
        violate: (honest) => ({ ...honest, http: honest.http.map((item) => ({ ...item, path: otherPath(path) })) }),
      },
      {
        leg,
        label: "a wrong-path entry before the good ones",
        detail: () => "entries",
        violate: (honest) => ({ ...honest, http: [entry(method, otherPath(path), 200), ...honest.http] }),
      },
    );
    if (rule.countFact !== null) {
      const countFact = rule.countFact;
      aspects.push(
        {
          leg,
          label: "one entry more than the count says",
          detail: () => `count:${countFact}`,
          violate: (honest) => ({ ...honest, http: [...honest.http, good()] }),
        },
        {
          leg,
          label: "one entry fewer than the count says",
          // A single-entry leg is left with none at all, which the entries rule names first.
          detail: (honest) => (honest.http.length > 1 ? `count:${countFact}` : "entries"),
          violate: (honest) => ({ ...honest, http: honest.http.slice(0, -1) }),
        },
      );
    }
    for (const [fact, position] of Object.entries(rule.statusFacts)) {
      aspects.push({
        leg,
        label: `the ${position} entry's status differs from ${fact}`,
        detail: () => `status:${fact}`,
        violate: (honest) => {
          const at = position === "first" ? 0 : honest.http.length - 1;
          return { ...honest, http: honest.http.map((item, index) => (index === at ? { ...item, status: 500 } : item)) };
        },
      });
    }
    if (rule.firstStatus !== null) {
      aspects.push({
        leg,
        label: "the first entry's status differs from firstStatus",
        detail: () => "firstStatus",
        violate: (honest) => ({ ...honest, http: honest.http.map((item, index) => (index === 0 ? { ...item, status: 500 } : item)) }),
      });
    }
    if (rule.allStatus !== null) {
      aspects.push({
        leg,
        label: "one entry's status differs from allStatus",
        detail: () => "allStatus",
        violate: (honest) => ({ ...honest, http: honest.http.map((item, index) => (index === honest.http.length - 1 ? { ...item, status: 500 } : item)) }),
      });
    }
  }
  return aspects;
}

describe("Step B 5b every passHttp rule of every leg has a violating case", () => {
  const allAspects = LEG_IDS.flatMap((leg) => aspectsOf(leg));

  it("covers every part of every rule of the table (deliberately counted: extending the table means extending this)", () => {
    const byLeg = (leg: LegId) => allAspects.filter((aspect) => aspect.leg === leg).length;
    // none-legs: 1 case each. Request legs: 5 entry cases + count (2) + each status fact + firstStatus + allStatus.
    expect(byLeg("L0")).toBe(1);
    expect(byLeg("L3")).toBe(1);
    expect(byLeg("L7")).toBe(1);
    expect(byLeg("L1")).toBe(5 + 1);
    expect(byLeg("L2")).toBe(5 + 1);
    expect(byLeg("L4")).toBe(5 + 1);
    expect(byLeg("L5")).toBe(5 + 2 + 1);
    expect(byLeg("L6")).toBe(5 + 2 + 1);
    expect(byLeg("L8")).toBe(5 + 2 + 1);
    expect(byLeg("L9")).toBe(5 + 2 + 1);
    expect(allAspects).toHaveLength(3 + 6 + 6 + 6 + 8 + 8 + 8 + 8);
  });

  it.each(allAspects.map((aspect) => [`${aspect.leg}: ${aspect.label}`, aspect] as const))(
    "rejects a passed leg with %s",
    async (_label, aspect) => {
      const honest = await honestPair(aspect.leg);
      // The honest pair is accepted, so any rejection below is caused by the violation alone.
      expect(passedError(aspect.leg, honest)).toBeNull();
      const error = passedError(aspect.leg, aspect.violate(honest));
      expect(error).toBeInstanceOf(LedgerValidationError);
      expect(error?.code).toBe("HTTP_CONTRADICT_PASS");
      expect(error?.message).toBe(`HTTP_CONTRADICT_PASS: ${aspect.detail(honest)}`);
    },
  );

  it.each(allAspects.map((aspect) => [`${aspect.leg}: ${aspect.label}`, aspect] as const))(
    "leaves the same pair unconstrained on a failed leg: %s",
    async (_label, aspect) => {
      const honest = await honestPair(aspect.leg);
      const { writer, lines } = memoryWriter();
      const violating = aspect.violate(honest);
      expect(() =>
        writer.writeLeg({ leg: aspect.leg, status: "failed", reason: "ASSERTION_FAILED", http: violating.http, facts: violating.facts }),
      ).not.toThrow();
      expect(lines).toHaveLength(1);
    },
  );
});
