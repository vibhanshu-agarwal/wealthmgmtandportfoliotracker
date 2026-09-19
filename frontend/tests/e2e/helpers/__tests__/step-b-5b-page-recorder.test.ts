// @vitest-environment node
import { describe, expect, it } from "vitest";
import { LEDGER_PATHS } from "../../../production-e2e/lib/contract";
import {
  httpOf,
  ledgerStatusOf,
  PAGE_READ_QUIET_MS,
  pageReadQuietState,
  PageRecorder,
  waitForQuietPageReads,
  type ConsoleMessageLike,
  type RequestLike,
  type ResponseLike,
} from "../../../production-e2e/lib/page-recorder";
import { evaluatePriceObservations } from "../../../production-e2e/lib/price-attribution";
import type { Clock } from "../../../production-e2e/lib/wait";

const FRONTEND = "https://vibhanshu-ai-portfolio.dev";
const API = "https://api.vibhanshu-ai-portfolio.dev";
const USER = "00000000-0000-0000-0000-0000000d3110";

function newRecorder(now?: () => number): PageRecorder {
  return new PageRecorder({ origins: { frontend: FRONTEND, api: API }, ledgerPaths: LEDGER_PATHS, userId: USER, now });
}

/** A clock that only moves when it is slept on or advanced: no test here waits for real. */
interface FakeClock extends Clock {
  advance(ms: number): void;
  readonly sleeps: number[];
}

function fakeClock(start = 1_000_000): FakeClock {
  let current = start;
  const sleeps: number[] = [];
  const clock: FakeClock = {
    now: () => current,
    sleep: async (ms) => {
      sleeps.push(ms);
      current += ms;
    },
    advance: (ms) => {
      current += ms;
    },
    sleeps,
  };
  return clock;
}

function fakeRequest(
  method: string,
  url: string,
  options: { headers?: Record<string, string>; postData?: string | null; allHeaders?: Record<string, string> } = {},
): RequestLike {
  const headers = options.headers ?? { authorization: "Bearer SECRET-TOKEN-VALUE" };
  return {
    url: () => url,
    method: () => method,
    headers: () => headers,
    postData: () => options.postData ?? null,
    allHeaders: async () => options.allHeaders ?? headers,
  };
}

function fakeResponse(
  request: RequestLike,
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): ResponseLike {
  return {
    status: () => status,
    headers: () => headers,
    request: () => request,
    json: async () => {
      if (body instanceof Error) throw body;
      return body;
    },
  };
}

const portfolioBody = (version: number, tickers: string[], userId = USER) => [
  {
    id: "p",
    userId,
    version,
    holdings: tickers.map((ticker, index) => ({ id: `h${index}`, assetTicker: ticker, quantity: "1.00000000" })),
  },
];

async function respond(recorder: PageRecorder, request: RequestLike, status: number, body: unknown, headers?: Record<string, string>) {
  await recorder.onResponse(fakeResponse(request, status, body, headers));
}

describe("Step B 5b page recorder: what it stores", () => {
  it("stores only requests to the API origin, with method and path template but no query", async () => {
    const recorder = newRecorder();
    const prices = fakeRequest("GET", `${API}/api/market/prices?tickers=AAPL%2CMSFT`);
    recorder.onRequest(prices);
    recorder.onRequest(fakeRequest("GET", `${FRONTEND}/_next/static/chunk.js`, { headers: {} }));
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio/analytics`));
    expect(recorder.entriesAfter(0).map((entry) => [entry.method, entry.path])).toEqual([
      ["GET", "/api/market/prices"],
      ["GET", null],
    ]);
    expect(recorder.entriesAfter(0)[0].tickers).toEqual(["AAPL", "MSFT"]);
    expect(Object.keys(recorder.entriesAfter(0)[0])).not.toContain("url");
  });

  it("marks a position in the stream and returns only later requests", () => {
    const recorder = newRecorder();
    recorder.onRequest(fakeRequest("GET", `${API}/api/assets`));
    const mark = recorder.mark();
    recorder.onRequest(fakeRequest("GET", `${API}/api/presence/demo`));
    expect(recorder.entriesAfter(mark).map((entry) => entry.path)).toEqual(["/api/presence/demo"]);
    expect(recorder.firstAfter(mark, "GET", "/api/assets")).toBeUndefined();
    expect(recorder.firstAfter(0, "GET", "/api/assets")?.path).toBe("/api/assets");
    expect(recorder.find("GET", "/api/presence/demo", mark)).toHaveLength(1);
  });

  it("records validators and the etag response header", async () => {
    const recorder = newRecorder();
    const fresh = fakeRequest("GET", `${API}/api/assets`, { headers: { authorization: "Bearer x" } });
    const revalidating = fakeRequest("GET", `${API}/api/assets`, {
      headers: { authorization: "Bearer x", "If-None-Match": '"v1"' },
    });
    recorder.onRequest(fresh);
    recorder.onRequest(revalidating);
    await respond(recorder, fresh, 200, { catalogVersion: "v1", assets: [{ ticker: "A", lifecycleStatus: "ACTIVE" }] }, { etag: '"v1"' });
    await respond(recorder, revalidating, 304, null);
    const [first, second] = recorder.find("GET", "/api/assets");
    expect(first).toMatchObject({ hasIfNoneMatch: false, hasAuthorization: true, etagPresent: true, status: 200, settled: true });
    expect(second).toMatchObject({ hasIfNoneMatch: true, etagPresent: false, status: 304, settled: true, parsed: null });
  });

  it("captures the JSON text of PUT bodies only", () => {
    const recorder = newRecorder();
    recorder.onRequest(fakeRequest("PUT", `${API}/api/portfolio/holdings`, { postData: '{"expectedVersion":1,"holdings":[]}' }));
    recorder.onRequest(fakeRequest("PUT", `${API}/api/portfolio/demo-reset`, { postData: '{"expectedVersion":2}' }));
    recorder.onRequest(fakeRequest("POST", `${API}/api/chat`, { postData: '{"message":"secret prompt"}' }));
    recorder.onRequest(fakeRequest("GET", `${API}/api/assets`, { postData: "ignored" }));
    expect(recorder.entriesAfter(0).map((entry) => entry.requestBodyText)).toEqual([
      '{"expectedVersion":1,"holdings":[]}',
      '{"expectedVersion":2}',
      null,
      null,
    ]);
  });

  it("ignores a response whose request it never recorded", async () => {
    const recorder = newRecorder();
    await respond(recorder, fakeRequest("GET", `${API}/api/assets`), 200, {});
    expect(recorder.entriesAfter(0)).toHaveLength(0);
  });
});

describe("Step B 5b page recorder: response parsing", () => {
  it("parses each known endpoint into its summary shape", async () => {
    const recorder = newRecorder();
    const requests = {
      portfolio: fakeRequest("GET", `${API}/api/portfolio`),
      summary: fakeRequest("GET", `${API}/api/portfolio/summary?userId=${USER}`),
      catalog: fakeRequest("GET", `${API}/api/assets`),
      presence: fakeRequest("GET", `${API}/api/presence/demo`),
      prices: fakeRequest("GET", `${API}/api/market/prices?tickers=A%2CB`),
      save: fakeRequest("PUT", `${API}/api/portfolio/holdings`),
      reset: fakeRequest("PUT", `${API}/api/portfolio/demo-reset`),
    };
    Object.values(requests).forEach((request) => recorder.onRequest(request));
    await respond(recorder, requests.portfolio, 200, portfolioBody(3, ["B", "A"]));
    await respond(recorder, requests.summary, 200, {
      assetPriceFreshness: { state: "STALE", staleHoldings: 2, unknownPriceHoldings: 0, missingPriceHoldings: 0 },
    });
    await respond(recorder, requests.catalog, 200, { catalogVersion: "v1", assets: [{ ticker: "A", lifecycleStatus: "ACTIVE" }] });
    await respond(recorder, requests.presence, 200, { anotherSessionActive: true });
    await respond(recorder, requests.prices, 200, [{ ticker: "A", currentPrice: 10 }]);
    await respond(recorder, requests.save, 200, portfolioBody(4, ["A"])[0]);
    await respond(recorder, requests.reset, 200, portfolioBody(5, ["A"])[0]);

    const parsedOf = (path: string, method = "GET") => recorder.find(method, path)[0].parsed;
    expect(parsedOf("/api/portfolio")).toMatchObject({ type: "portfolio", result: { ok: true } });
    expect(parsedOf("/api/portfolio/summary")).toEqual({
      type: "summary",
      freshness: { state: "STALE", countsValid: true },
    });
    expect(parsedOf("/api/assets")).toEqual({ type: "catalog", catalog: { assetsNonEmpty: true, activeTickers: ["A"] } });
    expect(parsedOf("/api/presence/demo")).toEqual({ type: "presence", anotherSessionActive: true });
    expect(parsedOf("/api/market/prices")).toEqual({ type: "prices", arrayShaped: true, nonNullPriceSeen: true });
    expect(parsedOf("/api/portfolio/holdings", "PUT")).toMatchObject({ type: "putResult", result: { ok: true } });
    expect(parsedOf("/api/portfolio/demo-reset", "PUT")).toMatchObject({ type: "putResult", result: { ok: true } });
  });

  it("does not parse a non-200 body and treats an unreadable 200 body as unparseable", async () => {
    const recorder = newRecorder();
    const limited = fakeRequest("GET", `${API}/api/presence/demo`);
    const broken = fakeRequest("GET", `${API}/api/assets`);
    recorder.onRequest(limited);
    recorder.onRequest(broken);
    await respond(recorder, limited, 429, { error: "rate_limited" });
    await respond(recorder, broken, 200, new Error("not json"));
    expect(recorder.find("GET", "/api/presence/demo")[0]).toMatchObject({ status: 429, parsed: null, settled: true });
    expect(recorder.find("GET", "/api/assets")[0]).toMatchObject({ status: 200, parsed: { type: "unparseable" }, settled: true });
  });

  it("stays unsettled until the body has been read", async () => {
    const recorder = newRecorder();
    const request = fakeRequest("GET", `${API}/api/presence/demo`);
    recorder.onRequest(request);
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const slow: ResponseLike = {
      status: () => 200,
      headers: () => ({}),
      request: () => request,
      json: async () => {
        await gate;
        return { anotherSessionActive: false };
      },
    };
    const pending = recorder.onResponse(slow);
    expect(recorder.find("GET", "/api/presence/demo")[0]).toMatchObject({ status: 200, settled: false });
    release();
    await pending;
    expect(recorder.find("GET", "/api/presence/demo")[0]).toMatchObject({ settled: true });
  });

  it("records a network failure as failed and settled", () => {
    const recorder = newRecorder();
    const request = fakeRequest("GET", `${API}/api/assets`);
    recorder.onRequest(request);
    const mark = recorder.mark();
    recorder.onRequestFailed(request);
    expect(recorder.find("GET", "/api/assets")[0]).toMatchObject({ failed: true, settled: true, status: null });
    expect(recorder.requestFailuresAfter(0)).toBe(1);
    expect(recorder.requestFailuresAfter(mark)).toBe(1);
    expect(recorder.requestFailuresAfter(mark + 1)).toBe(0);
    recorder.onRequestFailed(fakeRequest("GET", `${API}/api/never-seen`));
    expect(recorder.requestFailuresAfter(0)).toBe(1);
  });
});

describe("Step B 5b page recorder: portfolio observations", () => {
  it("keeps the latest identity-checked portfolio and every observed wire order", async () => {
    const recorder = newRecorder();
    const first = fakeRequest("GET", `${API}/api/portfolio`);
    const second = fakeRequest("GET", `${API}/api/portfolio`);
    const bad = fakeRequest("GET", `${API}/api/portfolio`);
    [first, second, bad].forEach((request) => recorder.onRequest(request));
    await respond(recorder, first, 200, portfolioBody(3, ["B", "A"]));
    await respond(recorder, second, 200, portfolioBody(4, ["A", "B"]));
    await respond(recorder, bad, 200, portfolioBody(9, ["Z"], "someone-else"));
    expect(recorder.lastGoodPortfolio()).toMatchObject({ version: 4 });
    expect(recorder.portfolioWireOrders()).toEqual([["B", "A"], ["A", "B"]]);
  });

  it("orders by response arrival, not request order", async () => {
    const recorder = newRecorder();
    const early = fakeRequest("GET", `${API}/api/portfolio`);
    const late = fakeRequest("GET", `${API}/api/portfolio`);
    recorder.onRequest(early);
    recorder.onRequest(late);
    await respond(recorder, late, 200, portfolioBody(8, ["A"]));
    await respond(recorder, early, 200, portfolioBody(7, ["A"]));
    expect(recorder.lastGoodPortfolio()).toMatchObject({ version: 7 });
  });

  it("returns null when no good portfolio was seen and reports the last status", async () => {
    const recorder = newRecorder();
    expect(recorder.lastGoodPortfolio()).toBeNull();
    expect(recorder.lastPortfolioStatus()).toBe(0);
    const request = fakeRequest("GET", `${API}/api/portfolio`);
    recorder.onRequest(request);
    // Nothing has settled: 0 only because there is no load outcome yet.
    expect(recorder.lastPortfolioStatus()).toBe(0);
    expect(recorder.settledPortfolioReads()).toHaveLength(0);
    await respond(recorder, request, 503, null);
    expect(recorder.lastPortfolioStatus()).toBe(503);
    expect(recorder.lastGoodPortfolio()).toBeNull();
    const next = fakeRequest("GET", `${API}/api/portfolio`);
    recorder.onRequest(next);
    // The refetch in flight does not replace the settled 503 as the load outcome.
    expect(recorder.lastPortfolioStatus()).toBe(503);
    recorder.onRequestFailed(next);
    expect(recorder.lastPortfolioStatus()).toBe(0);
  });

  it("judges the last SETTLED portfolio read: a trailing in-flight refetch is not a load outcome", async () => {
    const recorder = newRecorder();
    const load = fakeRequest("GET", `${API}/api/portfolio`);
    recorder.onRequest(load);
    await respond(recorder, load, 200, portfolioBody(3, ["A"]));
    expect(recorder.lastPortfolioStatus()).toBe(200);

    // The page's 60 s refetch starts: still in flight, so it neither lowers the status to 0 nor is listed.
    const refetch = fakeRequest("GET", `${API}/api/portfolio`);
    recorder.onRequest(refetch);
    expect(recorder.lastPortfolioStatus()).toBe(200);
    expect(recorder.find("GET", "/api/portfolio")).toHaveLength(2);
    expect(recorder.settledPortfolioReads()).toHaveLength(1);
    expect(httpOf(recorder.settledPortfolioReads())).toEqual([{ method: "GET", path: "/api/portfolio", status: 200 }]);

    // Once it settles it IS the last load outcome, whatever it says.
    await respond(recorder, refetch, 500, null);
    expect(recorder.lastPortfolioStatus()).toBe(500);
    expect(recorder.settledPortfolioReads()).toHaveLength(2);
  });

  it("keeps lastPortfolioStatus equal to the status of the last entry of the settled reads, in every state", async () => {
    const recorder = newRecorder();
    const record = (): RequestLike => {
      const request = fakeRequest("GET", `${API}/api/portfolio`);
      recorder.onRequest(request);
      return request;
    };
    const steps: Array<() => Promise<void> | void> = [
      () => undefined,
      () => void record(),
      async () => respond(recorder, record(), 200, portfolioBody(3, ["A"])),
      () => void record(),
      async () => respond(recorder, record(), 503, null),
      () => recorder.onRequestFailed(record()),
      () => void record(),
    ];
    for (const [index, step] of steps.entries()) {
      await step();
      const reads = httpOf(recorder.settledPortfolioReads());
      const last = reads[reads.length - 1];
      expect(recorder.lastPortfolioStatus(), `after step ${index}`).toBe(last === undefined ? 0 : last.status);
    }
  });
});

describe("Step B 5b page recorder: the ledger view of a request", () => {
  it("maps a failed or unanswered request to status 0 and an answered one to its status", async () => {
    const recorder = newRecorder();
    const answered = fakeRequest("GET", `${API}/api/assets`);
    const failed = fakeRequest("GET", `${API}/api/assets`);
    const pending = fakeRequest("GET", `${API}/api/assets`);
    [answered, failed, pending].forEach((request) => recorder.onRequest(request));
    await respond(recorder, answered, 304, null);
    recorder.onRequestFailed(failed);
    const [a, f, p] = recorder.find("GET", "/api/assets");
    expect([ledgerStatusOf(a), ledgerStatusOf(f), ledgerStatusOf(p), ledgerStatusOf(undefined)]).toEqual([304, 0, 0, 0]);
  });

  it("records method, path template and that status for GET and PUT entries, and skips anything else", async () => {
    const recorder = newRecorder();
    const save = fakeRequest("PUT", `${API}/api/portfolio/holdings`);
    const read = fakeRequest("GET", `${API}/api/assets?x=1`);
    recorder.onRequest(save);
    recorder.onRequest(read);
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio/analytics`));
    recorder.onRequest(fakeRequest("POST", `${API}/api/chat`));
    await respond(recorder, save, 200, portfolioBody(4, ["A"])[0]);
    expect(httpOf(recorder.entriesAfter(0))).toEqual([
      { method: "PUT", path: "/api/portfolio/holdings", status: 200 },
      { method: "GET", path: "/api/assets", status: 0 },
    ]);
  });
});

describe("Step B 5b page recorder: settle times and the pacing before a write", () => {
  it("stamps each request with the injected clock when it settles, by response or by failure", async () => {
    const clock = fakeClock(5_000);
    const recorder = newRecorder(clock.now);
    const answered = fakeRequest("GET", `${API}/api/portfolio`);
    const failed = fakeRequest("GET", `${API}/api/market/prices?tickers=A`);
    recorder.onRequest(answered);
    recorder.onRequest(failed);
    expect(recorder.pageReads().map((entry) => entry.settledAtMs)).toEqual([null, null]);
    clock.advance(700);
    await respond(recorder, answered, 200, portfolioBody(1, ["A"]));
    clock.advance(300);
    recorder.onRequestFailed(failed);
    expect(recorder.pageReads().map((entry) => entry.settledAtMs)).toEqual([5_700, 6_000]);
  });

  it("lists only the page-side GET portfolio and price reads for pacing", () => {
    const recorder = newRecorder();
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio`));
    recorder.onRequest(fakeRequest("GET", `${API}/api/market/prices?tickers=A`));
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio/summary`));
    recorder.onRequest(fakeRequest("GET", `${API}/api/assets`));
    recorder.onRequest(fakeRequest("PUT", `${API}/api/portfolio/holdings`));
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio/analytics`));
    expect(recorder.pageReads().map((entry) => [entry.method, entry.path])).toEqual([
      ["GET", "/api/portfolio"],
      ["GET", "/api/market/prices"],
    ]);
  });

  describe("pageReadQuietState (pure)", () => {
    const settled = (settledAtMs: number) => ({ settled: true, settledAtMs });
    const inFlight = { settled: false, settledAtMs: null };

    it("is in_flight while any read has not settled, however long ago the others did", () => {
      expect(pageReadQuietState([settled(0), inFlight], 1_000_000)).toEqual({ state: "in_flight" });
      expect(pageReadQuietState([inFlight], 1_000_000)).toEqual({ state: "in_flight" });
      // Missing information never reads as quiet.
      expect(pageReadQuietState([{ settled: true, settledAtMs: null }], 1_000_000)).toEqual({ state: "in_flight" });
    });

    it("is settling until the quiet period has passed since the LATEST settle, and says how much longer", () => {
      expect(pageReadQuietState([settled(10_000)], 10_000)).toEqual({ state: "settling", quietInMs: PAGE_READ_QUIET_MS });
      expect(pageReadQuietState([settled(10_000), settled(11_500)], 12_000)).toEqual({ state: "settling", quietInMs: 1_500 });
      expect(pageReadQuietState([settled(11_500), settled(10_000)], 13_499)).toEqual({ state: "settling", quietInMs: 1 });
    });

    it("is quiet once the quiet period has passed since the latest settle, and for an empty list", () => {
      expect(pageReadQuietState([settled(10_000)], 12_000)).toEqual({ state: "quiet" });
      expect(pageReadQuietState([settled(10_000), settled(11_500)], 13_500)).toEqual({ state: "quiet" });
      expect(pageReadQuietState([], 0)).toEqual({ state: "quiet" });
    });

    it("uses about two seconds by default and honours an explicit period", () => {
      expect(PAGE_READ_QUIET_MS).toBe(2_000);
      expect(pageReadQuietState([settled(0)], 1_999)).toMatchObject({ state: "settling" });
      expect(pageReadQuietState([settled(0)], 2_000)).toEqual({ state: "quiet" });
      expect(pageReadQuietState([settled(0)], 500, 500)).toEqual({ state: "quiet" });
      expect(pageReadQuietState([settled(0)], 499, 500)).toMatchObject({ state: "settling" });
    });

    it("never reads as quiet for a clock that is not a number, or one that ran backwards", () => {
      expect(pageReadQuietState([settled(10_000)], Number.NaN)).toMatchObject({ state: "settling" });
      expect(pageReadQuietState([settled(10_000)], 9_000)).toEqual({ state: "settling", quietInMs: PAGE_READ_QUIET_MS });
    });
  });

  describe("waitForQuietPageReads (bounded, on the injected clock)", () => {
    const options = (clock: Clock, timeoutMs = 60_000) => ({ timeoutMs, intervalMs: 250, clock });

    it("returns at once when there is nothing to wait for or the reads settled long ago", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      expect(await waitForQuietPageReads(recorder, options(clock))).toBe(true);
      expect(clock.sleeps).toEqual([]);

      const load = fakeRequest("GET", `${API}/api/portfolio`);
      recorder.onRequest(load);
      await respond(recorder, load, 200, portfolioBody(1, ["A"]));
      clock.advance(PAGE_READ_QUIET_MS + 1);
      expect(await waitForQuietPageReads(recorder, options(clock))).toBe(true);
      expect(clock.sleeps).toEqual([]);
    });

    it("waits out the quiet period after the latest settle, polling on the injected clock and never for real", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      const load = fakeRequest("GET", `${API}/api/portfolio`);
      recorder.onRequest(load);
      await respond(recorder, load, 200, portfolioBody(1, ["A"]));
      const settledAt = clock.now();
      expect(await waitForQuietPageReads(recorder, options(clock))).toBe(true);
      expect(clock.now() - settledAt).toBeGreaterThanOrEqual(PAGE_READ_QUIET_MS);
      expect(clock.now() - settledAt).toBeLessThan(PAGE_READ_QUIET_MS + 250);
      expect(clock.sleeps.every((ms) => ms <= 250)).toBe(true);
    });

    it("waits for an in-flight read to settle and then for the quiet period after it", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      const price = fakeRequest("GET", `${API}/api/market/prices?tickers=A`);
      recorder.onRequest(price);
      // The response arrives 3 s into the wait (a cold service), from inside the poll's own sleep.
      const advanceClock = clock.sleep;
      let answered = false;
      clock.sleep = async (ms) => {
        await advanceClock(ms);
        if (!answered && clock.now() >= 1_003_000) {
          answered = true;
          await respond(recorder, price, 200, [{ ticker: "A", currentPrice: 1 }]);
        }
      };
      expect(await waitForQuietPageReads(recorder, options(clock))).toBe(true);
      expect(answered).toBe(true);
      const settledAt = recorder.pageReads()[0].settledAtMs as number;
      expect(settledAt).toBeGreaterThanOrEqual(1_003_000);
      expect(clock.now() - settledAt).toBeGreaterThanOrEqual(PAGE_READ_QUIET_MS);
    });

    it("gives up at the deadline when a read never settles, having slept only on the injected clock", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      recorder.onRequest(fakeRequest("GET", `${API}/api/market/prices?tickers=A`));
      const started = clock.now();
      expect(await waitForQuietPageReads(recorder, options(clock, 5_000))).toBe(false);
      expect(clock.now() - started).toBe(5_000);
      expect(clock.sleeps.reduce((sum, ms) => sum + ms, 0)).toBe(5_000);
    });

    it("gives up at the deadline when the page never goes quiet for long enough", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      const first = fakeRequest("GET", `${API}/api/portfolio`);
      recorder.onRequest(first);
      await respond(recorder, first, 200, portfolioBody(1, ["A"]));
      // A fresh read settles every second: the quiet period (2 s) is never reached inside the 6 s allowed.
      const advanceClock = clock.sleep;
      let elapsed = 0;
      clock.sleep = async (ms) => {
        await advanceClock(ms);
        elapsed += ms;
        if (elapsed % 1_000 === 0) {
          const read = fakeRequest("GET", `${API}/api/portfolio`);
          recorder.onRequest(read);
          await respond(recorder, read, 200, portfolioBody(1, ["A"]));
        }
      };
      expect(await waitForQuietPageReads(recorder, options(clock, 6_000))).toBe(false);
    });

    it("counts a failed read as settled, at the moment it failed", async () => {
      const clock = fakeClock();
      const recorder = newRecorder(clock.now);
      const aborted = fakeRequest("GET", `${API}/api/market/prices?tickers=A`);
      recorder.onRequest(aborted);
      recorder.onRequestFailed(aborted);
      expect(await waitForQuietPageReads(recorder, options(clock))).toBe(true);
      expect(clock.now() - 1_000_000).toBeGreaterThanOrEqual(PAGE_READ_QUIET_MS);
    });
  });
});

describe("Step B 5b page recorder: safety observations", () => {
  it("counts every non-GET/OPTIONS API request as a write, including ones outside the path list", () => {
    const recorder = newRecorder();
    recorder.onRequest(fakeRequest("GET", `${API}/api/portfolio`));
    recorder.onRequest(fakeRequest("OPTIONS", `${API}/api/portfolio/holdings`));
    expect(recorder.writeRequestCount()).toBe(0);
    recorder.onRequest(fakeRequest("POST", `${API}/api/chat`));
    recorder.onRequest(fakeRequest("PUT", `${API}/api/portfolio/holdings`));
    recorder.onRequest(fakeRequest("DELETE", `${API}/api/whatever`));
    recorder.onRequest(fakeRequest("HEAD", `${API}/api/assets`));
    expect(recorder.writeRequestCount()).toBe(4);
  });

  it("does not count writes to other origins as API writes, but flags a bearer sent there", () => {
    const recorder = newRecorder();
    recorder.onRequest(fakeRequest("POST", "https://telemetry.example/collect", { headers: {} }));
    expect(recorder.writeRequestCount()).toBe(0);
    expect(recorder.foreignAuthorizedRequestSeen()).toBe(false);
    recorder.onRequest(fakeRequest("GET", "https://evil.example/steal", { headers: { Authorization: "Bearer x" } }));
    expect(recorder.foreignAuthorizedRequestSeen()).toBe(true);
    expect(recorder.entriesAfter(0)).toHaveLength(0);
  });

  it("sees a 401 on any API path", async () => {
    const recorder = newRecorder();
    const request = fakeRequest("GET", `${API}/api/portfolio/analytics`);
    recorder.onRequest(request);
    expect(recorder.unauthorized401Seen()).toBe(false);
    await respond(recorder, request, 401, null);
    expect(recorder.unauthorized401Seen()).toBe(true);
  });

  it("detects a CORS console error by its signature, stamps it, and keeps no text", () => {
    const recorder = newRecorder();
    const message = (type: string, text: string): ConsoleMessageLike => ({ type: () => type, text: () => text });
    recorder.onConsole(message("error", "Some ordinary error with page text"));
    recorder.onConsole(message("log", "blocked by CORS policy"));
    expect(recorder.corsConsoleErrorsAfter(0)).toBe(0);
    const mark = recorder.mark();
    recorder.onConsole(message("error", "Access to fetch at 'https://api.example/x' has been blocked by CORS policy"));
    expect(recorder.corsConsoleErrorsAfter(mark)).toBe(1);
    expect(recorder.corsConsoleErrorsAfter(recorder.mark())).toBe(0);
    expect(JSON.stringify(recorder)).not.toContain("blocked");
  });

  it("notices a navigation to /login on the frontend origin only", () => {
    const recorder = newRecorder();
    recorder.onMainFrameNavigated("about:blank");
    recorder.onMainFrameNavigated(`${FRONTEND}/portfolio`);
    recorder.onMainFrameNavigated("https://evil.example/login");
    expect(recorder.visitedLogin()).toBe(false);
    recorder.onMainFrameNavigated(`${FRONTEND}/login?next=%2Fportfolio`);
    expect(recorder.visitedLogin()).toBe(true);
  });
});

describe("Step B 5b page recorder: price observations for L6", () => {
  it("only reports price requests emitted after the mark and evaluates them", async () => {
    const recorder = newRecorder();
    const before = fakeRequest("GET", `${API}/api/market/prices?tickers=T000%2CT001`);
    recorder.onRequest(before);
    const mark = recorder.mark();
    const after = fakeRequest("GET", `${API}/api/market/prices?tickers=T001%2CT002`);
    const other = fakeRequest("GET", `${API}/api/market/prices?tickers=X1`);
    recorder.onRequest(after);
    recorder.onRequest(other);
    // The old request answers late, after the mark: it must not be attributed.
    await respond(recorder, before, 500, null);
    await respond(recorder, after, 200, [{ ticker: "T001", currentPrice: 1 }]);
    await respond(recorder, other, 429, null);

    const observations = recorder.priceObservationsAfter(mark);
    expect(observations.map((observation) => observation.tickers)).toEqual([["T001", "T002"], ["X1"]]);
    const evaluation = evaluatePriceObservations(observations, [["T001", "T002"]]);
    // The recorder still reports both requests after the mark, but only the predicted picker batch is
    // judged: the page-side X1 request answered 429 and is not attributed to route 9.3.
    expect(evaluation.attributedCount).toBe(1);
    expect(evaluation.priceRequestsAfterUncheck).toBe(1);
    expect(evaluation.firstBadStatus).toBeNull();
    expect(evaluation.allStatus200).toBe(true);
    expect(evaluation.nonNullPriceSeen).toBe(true);
    expect(evaluation.allPredictedBatchesSeen).toBe(true);
  });
});
