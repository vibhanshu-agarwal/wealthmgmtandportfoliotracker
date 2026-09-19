// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  independentPortfolioRead,
  type FetchInit,
  type FetchLike,
  type FetchResponseLike,
} from "../../../production-e2e/lib/independent-read";
import { pollUntil, waitHidden, waitVisible, type Clock, type LocatorLike } from "../../../production-e2e/lib/wait";

const API = "https://api.vibhanshu-ai-portfolio.dev";
const USER = "00000000-0000-0000-0000-0000000d3110";
const TOKEN = "SENTINEL-TOKEN-VALUE";

const portfolio = (userId: string, version: number, holdings: Array<[string, string]>) => ({
  id: `p-${userId}`,
  userId,
  version,
  holdings: holdings.map(([assetTicker, quantity], index) => ({ id: `h${index}`, assetTicker, quantity })),
});

function recordingFetch(reply: () => Promise<FetchResponseLike>) {
  const calls: Array<{ url: string; init: FetchInit }> = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    return reply();
  };
  return { fetchImpl, calls };
}

const jsonResponse = (status: number, body: unknown): Promise<FetchResponseLike> =>
  Promise.resolve({ status, json: async () => body });

const read = (fetchImpl: FetchLike) =>
  independentPortfolioRead({ fetchImpl, apiOrigin: API, token: TOKEN, userId: USER, timeoutMs: 5_000 });

describe("Step B 5b independent read", () => {
  it("issues one plain GET of /api/portfolio with the bearer token, no cache, no redirects", async () => {
    const { fetchImpl, calls } = recordingFetch(() => jsonResponse(200, [portfolio(USER, 6, [["AAPL", "32.00000000"]])]));
    await read(fetchImpl);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(`${API}/api/portfolio`);
    expect(calls[0].init).toMatchObject({
      method: "GET",
      cache: "no-store",
      redirect: "error",
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    expect(calls[0].init.signal).toBeInstanceOf(AbortSignal);
  });

  it("returns the identity-checked snapshot", async () => {
    const { fetchImpl } = recordingFetch(() =>
      jsonResponse(200, [
        portfolio("someone-else", 99, [["ZZZ", "1.00000000"]]),
        portfolio(USER, 6, [["AAPL", "32.00000000"], ["MSFT", "5.00000000"]]),
      ]),
    );
    expect(await read(fetchImpl)).toEqual({
      status: 200,
      snapshot: {
        userId: USER,
        version: 6,
        holdings: [
          { ticker: "AAPL", quantity: "32.00000000" },
          { ticker: "MSFT", quantity: "5.00000000" },
        ],
      },
    });
  });

  it.each([
    ["no entry for the user", []],
    ["only another user's entry", [portfolio("someone-else", 1, [])]],
    ["two entries for the user", [portfolio(USER, 1, []), portfolio(USER, 2, [])]],
    ["a non-array body", { userId: USER }],
    ["a numeric quantity", [{ id: "p", userId: USER, version: 1, holdings: [{ id: "h", assetTicker: "AAPL", quantity: 32 }] }]],
  ])("gives no snapshot for %s", async (_label, body) => {
    const { fetchImpl } = recordingFetch(() => jsonResponse(200, body));
    expect(await read(fetchImpl)).toEqual({ status: 200, snapshot: null });
  });

  it.each([401, 403, 429, 500, 503, 504])("reports HTTP %i without a snapshot", async (status) => {
    const { fetchImpl } = recordingFetch(() => jsonResponse(status, [portfolio(USER, 1, [])]));
    expect(await read(fetchImpl)).toEqual({ status, snapshot: null });
  });

  it("reports status 0 when no response arrives and never throws", async () => {
    const { fetchImpl } = recordingFetch(() => Promise.reject(new Error(`network down for Bearer ${TOKEN}`)));
    const result = await read(fetchImpl);
    expect(result).toEqual({ status: 0, snapshot: null });
    expect(JSON.stringify(result)).not.toContain(TOKEN);
  });

  it("gives no snapshot when the body is not JSON", async () => {
    const { fetchImpl } = recordingFetch(() =>
      Promise.resolve({
        status: 200,
        json: async () => {
          throw new SyntaxError("Unexpected token");
        },
      }),
    );
    expect(await read(fetchImpl)).toEqual({ status: 200, snapshot: null });
  });

  it("never carries the token in its result", async () => {
    const { fetchImpl } = recordingFetch(() => jsonResponse(200, [portfolio(USER, 1, [["AAPL", "1.00000000"]])]));
    expect(JSON.stringify(await read(fetchImpl))).not.toContain(TOKEN);
  });
});

describe("Step B 5b bounded waiting", () => {
  function fakeClock(): Clock & { sleeps: number[]; time: () => number } {
    let now = 0;
    const sleeps: number[] = [];
    return {
      now: () => now,
      sleep: async (ms) => {
        sleeps.push(ms);
        now += ms;
      },
      sleeps,
      time: () => now,
    };
  }

  it("resolves true as soon as the predicate holds, without sleeping past it", async () => {
    const clock = fakeClock();
    let calls = 0;
    const result = await pollUntil(() => ++calls >= 3, { timeoutMs: 10_000, intervalMs: 250, clock });
    expect(result).toBe(true);
    expect(clock.sleeps).toEqual([250, 250]);
  });

  it("resolves false at the deadline using only injected time, and clamps the last sleep", async () => {
    const clock = fakeClock();
    const result = await pollUntil(() => false, { timeoutMs: 1_000, intervalMs: 300, clock });
    expect(result).toBe(false);
    expect(clock.sleeps).toEqual([300, 300, 300, 100]);
    expect(clock.time()).toBe(1_000);
  });

  it("checks the predicate once even with a zero timeout, and awaits async predicates", async () => {
    const clock = fakeClock();
    expect(await pollUntil(async () => true, { timeoutMs: 0, intervalMs: 250, clock })).toBe(true);
    expect(await pollUntil(async () => false, { timeoutMs: 0, intervalMs: 250, clock })).toBe(false);
    expect(clock.sleeps).toEqual([]);
  });

  it("propagates a predicate failure instead of retrying it", async () => {
    const clock = fakeClock();
    await expect(
      pollUntil(
        () => {
          throw new Error("page closed");
        },
        { timeoutMs: 1_000, intervalMs: 250, clock },
      ),
    ).rejects.toThrow("page closed");
  });

  const locator = (outcome: () => Promise<void>): LocatorLike => ({ waitFor: outcome });
  const timeoutError = (): Error => Object.assign(new Error("Timeout 1000ms exceeded"), { name: "TimeoutError" });

  it("turns a Playwright TimeoutError into false and rethrows anything else", async () => {
    expect(await waitVisible(locator(async () => undefined), 1_000)).toBe(true);
    expect(await waitHidden(locator(async () => undefined), 1_000)).toBe(true);
    expect(await waitVisible(locator(async () => Promise.reject(timeoutError())), 1_000)).toBe(false);
    expect(await waitHidden(locator(async () => Promise.reject(timeoutError())), 1_000)).toBe(false);
    await expect(waitVisible(locator(async () => Promise.reject(new Error("Target page closed"))), 1_000)).rejects.toThrow(
      "Target page closed",
    );
  });

  it("asks for the requested state and timeout", async () => {
    const seen: Array<{ state: string; timeout: number }> = [];
    const spy: LocatorLike = { waitFor: async (options) => void seen.push(options) };
    await waitVisible(spy, 1_234);
    await waitHidden(spy, 5_678);
    expect(seen).toEqual([
      { state: "visible", timeout: 1_234 },
      { state: "hidden", timeout: 5_678 },
    ]);
  });
});
