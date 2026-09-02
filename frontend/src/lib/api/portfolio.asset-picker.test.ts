/**
 * B2 Wave 1 Task 1.2 + Wave 2 Tasks 2.1/2.3 — the frontend portfolio adapter contract the
 * Asset Picker depends on.
 *
 * Covers three things the picker cannot be built without:
 *   1. the ingestion boundary's honesty about which quantities are byte-faithful (2.1);
 *   2. `version` reaching the domain type so a save can carry `expectedVersion` (1.2); and
 *   3. list-identity selection — B1's Primary_Portfolio invariant promises exactly one
 *      portfolio per user, so anything else is a contract failure, never `portfolios[0]`.
 */
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import {
  PortfolioContractError,
  buildPortfolioResponseFromWireHoldings,
  enrichWireHoldings,
  fetchPortfolio,
  parseWireQuantity,
  selectPortfolioForUser,
} from "./portfolio";
import type { BackendPortfolio, WireHolding } from "./portfolio";

const TOKEN = "test-token";

function wirePortfolio(overrides: Partial<BackendPortfolio> = {}): BackendPortfolio {
  return {
    id: "portfolio-001",
    userId: "user-001",
    name: "My Portfolio",
    version: 7,
    createdAt: "2026-01-01T00:00:00Z",
    holdings: [] as WireHolding[],
    ...overrides,
  };
}

function servePortfolios(payload: BackendPortfolio[]) {
  server.use(http.get("/api/portfolio", () => HttpResponse.json(payload)));
}

beforeEach(() => {
  server.use(
    http.get("/api/market/prices", ({ request }) => {
      const tickers =
        new URL(request.url).searchParams.get("tickers")?.split(",").filter(Boolean) ?? [];
      return HttpResponse.json(
        tickers.map((ticker) => ({
          ticker,
          currentPrice: 100,
          observedAt: "2026-08-01T00:00:00Z",
          priceUnavailable: false,
        })),
      );
    }),
  );
});

// ── Task 2.1: the ingestion boundary ────────────────────────────────────────

describe("parseWireQuantity (Task 2.1)", () => {
  it("preserves a string wire value verbatim, byte for byte", () => {
    expect(parseWireQuantity("0.75000000")).toEqual({
      quantity: "0.75000000",
      quantityFidelityUnverified: false,
    });
  });

  it("preserves trailing zeros and a leading zero exactly as sent", () => {
    expect(parseWireQuantity("10.00000000").quantity).toBe("10.00000000");
    expect(parseWireQuantity("0.00000001").quantity).toBe("0.00000001");
  });

  it("converts a number wire value with String() and marks it unverified", () => {
    // A JSON number has already lost its wire formatting by the time JS parses it:
    // "0.75000000" arrived as 0.75 and nothing remembers there were eight fractional
    // digits. The conversion is display compatibility only, never a fidelity claim.
    expect(parseWireQuantity(0.75)).toEqual({
      quantity: "0.75",
      quantityFidelityUnverified: true,
    });
  });

  it("marks every numeric holding unverified, including integers", () => {
    expect(parseWireQuantity(10)).toEqual({
      quantity: "10",
      quantityFidelityUnverified: true,
    });
  });
});

describe("fetchPortfolio quantity ingestion (Tasks 2.1/2.3)", () => {
  it("carries a string wire quantity into the domain type verbatim and unflagged", async () => {
    servePortfolios([
      wirePortfolio({
        holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "12.50000000" }],
      }),
    ]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.holdings[0].quantity).toBe("12.50000000");
    expect(portfolio.holdings[0].quantityFidelityUnverified).toBeUndefined();
  });

  it("flags a numeric wire quantity as fidelity-unverified", async () => {
    servePortfolios([
      wirePortfolio({ holdings: [{ id: "h1", assetTicker: "AAPL", quantity: 12.5 }] }),
    ]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.holdings[0].quantity).toBe("12.5");
    expect(portfolio.holdings[0].quantityFidelityUnverified).toBe(true);
  });

  it("still derives totalValue from a string quantity at the display boundary", async () => {
    servePortfolios([
      wirePortfolio({ holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "2.5" }] }),
    ]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.holdings[0].totalValue).toBe(250);
    // The conversion never flows back into the domain state.
    expect(portfolio.holdings[0].quantity).toBe("2.5");
  });
});

// ── Task 1.2: version on the domain contract ────────────────────────────────

describe("fetchPortfolio version (Task 1.2)", () => {
  it("carries the wire version onto the domain response", async () => {
    servePortfolios([wirePortfolio({ version: 7 })]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.version).toBe(7);
  });

  it("reports expected version 0 for the valid no-portfolio state", async () => {
    servePortfolios([]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.version).toBe(0);
    expect(portfolio.holdings).toEqual([]);
    // The auto-provision sentinel is a genuine, meaningful backend contract
    // value (B1 auto-provisions on expectedVersion 0), not a data gap.
    expect(portfolio.versionObserved).toBe(true);
  });

  it("marks a genuinely observed version as observed, including zero", async () => {
    servePortfolios([wirePortfolio({ version: 0 })]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.version).toBe(0);
    expect(portfolio.versionObserved).toBe(true);
  });

  it("does not report a missing wire version as observed, even though it is defaulted to 0", async () => {
    // A portfolio genuinely exists (unlike the no-portfolio case above), but this
    // response omits `version` entirely — the currently-deployed backend predates
    // it. `fetchPortfolio`'s own `?? 0` keeps existing callers (composition save)
    // working, but that 0 is a client-side invention, not something the server
    // confirmed — callers that must never send a fabricated version (B2 Task 6.2)
    // need a way to tell the two apart.
    servePortfolios([
      {
        id: "portfolio-001",
        userId: "user-001",
        name: "My Portfolio",
        createdAt: "2026-01-01T00:00:00Z",
        holdings: [],
      },
    ]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.version).toBe(0);
    expect(portfolio.versionObserved).toBe(false);
  });
});

// ── Task 1.2: list-identity selection ───────────────────────────────────────

describe("selectPortfolioForUser (Task 1.2)", () => {
  it("returns null for an empty list — the valid no-portfolio state", () => {
    expect(selectPortfolioForUser([], "user-001")).toBeNull();
  });

  it("returns the single entry matching the caller's userId", () => {
    const mine = wirePortfolio({ id: "p-mine", userId: "user-001" });
    expect(selectPortfolioForUser([mine], "user-001")).toBe(mine);
  });

  it("selects by userId, not by list position", () => {
    const other = wirePortfolio({ id: "p-other", userId: "user-999" });
    const mine = wirePortfolio({ id: "p-mine", userId: "user-001" });

    // The pre-B2 adapter returned portfolios[0] — another user's portfolio here.
    expect(selectPortfolioForUser([other, mine], "user-001")).toBe(mine);
  });

  it("fails the contract on a non-empty list with zero matches", () => {
    const other = wirePortfolio({ id: "p-other", userId: "user-999" });

    expect(() => selectPortfolioForUser([other], "user-001")).toThrow(PortfolioContractError);
  });

  it("fails the contract on more than one match", () => {
    const a = wirePortfolio({ id: "p-a", userId: "user-001" });
    const b = wirePortfolio({ id: "p-b", userId: "user-001" });

    expect(() => selectPortfolioForUser([a, b], "user-001")).toThrow(PortfolioContractError);
  });
});

describe("fetchPortfolio list-identity selection (Task 1.2)", () => {
  it("reads the caller's own portfolio out of a multi-entry list", async () => {
    servePortfolios([
      wirePortfolio({ id: "p-other", userId: "user-999", version: 3, holdings: [] }),
      wirePortfolio({
        id: "p-mine",
        userId: "user-001",
        version: 9,
        holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "1" }],
      }),
    ]);

    const portfolio = await fetchPortfolio("user-001", TOKEN);

    expect(portfolio.portfolioId).toBe("p-mine");
    expect(portfolio.version).toBe(9);
  });

  it("surfaces an error rather than silently using an arbitrary element", async () => {
    servePortfolios([wirePortfolio({ id: "p-other", userId: "user-999" })]);

    await expect(fetchPortfolio("user-001", TOKEN)).rejects.toBeInstanceOf(
      PortfolioContractError,
    );
  });
});

// ── Task 1.13 (requirements.md 4.2): shared holding enrichment ─────────────

describe("enrichWireHoldings", () => {
  it("enriches wire holdings with price and computes totalValue/portfolioWeight", async () => {
    server.use(
      http.get("/api/market/prices", () =>
        HttpResponse.json([
          { ticker: "AAPL", currentPrice: 100, observedAt: "2026-08-01T00:00:00Z", priceUnavailable: false },
        ]),
      ),
    );

    const { holdings, totalValue } = await enrichWireHoldings(
      [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
      TOKEN,
    );

    expect(totalValue).toBe(1000);
    expect(holdings[0]).toMatchObject({
      id: "h1",
      ticker: "AAPL",
      quantity: "10",
      currentPrice: 100,
      totalValue: 1000,
      portfolioWeight: 100,
    });
  });

  it("flags a numeric wire quantity as fidelity-unverified, same as fetchPortfolio", async () => {
    server.use(http.get("/api/market/prices", () => HttpResponse.json([])));

    const { holdings } = await enrichWireHoldings(
      [{ id: "h1", assetTicker: "AAPL", quantity: 10 }],
      TOKEN,
    );
    expect(holdings[0].quantityFidelityUnverified).toBe(true);
  });

  it("never coerces an unavailable price to 0", async () => {
    server.use(
      http.get("/api/market/prices", () =>
        HttpResponse.json([{ ticker: "ZZZZ", currentPrice: null, priceUnavailable: true }]),
      ),
    );

    const { holdings } = await enrichWireHoldings(
      [{ id: "h2", assetTicker: "ZZZZ", quantity: "5" }],
      TOKEN,
    );
    expect(holdings[0].currentPrice).toBe(0);
    expect(holdings[0].totalValue).toBe(0);
  });

  it("returns zero holdings and zero totalValue for an empty list, without a price fetch", async () => {
    let called = false;
    server.use(
      http.get("/api/market/prices", () => {
        called = true;
        return HttpResponse.json([]);
      }),
    );
    const { holdings, totalValue } = await enrichWireHoldings([], TOKEN);
    expect(holdings).toEqual([]);
    expect(totalValue).toBe(0);
    expect(called).toBe(false);
  });
});

describe("buildPortfolioResponseFromWireHoldings (requirements.md 4.2)", () => {
  it("assembles a full PortfolioResponseDTO from the given identity and wire holdings", async () => {
    server.use(
      http.get("/api/market/prices", () =>
        HttpResponse.json([
          { ticker: "AAPL", currentPrice: 100, observedAt: "2026-08-01T00:00:00Z", priceUnavailable: false },
        ]),
      ),
    );

    const portfolio = await buildPortfolioResponseFromWireHoldings(
      { portfolioId: "p1", ownerId: "user-001", version: 8 },
      [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
      TOKEN,
    );

    expect(portfolio.portfolioId).toBe("p1");
    expect(portfolio.ownerId).toBe("user-001");
    expect(portfolio.version).toBe(8);
    expect(portfolio.holdings[0]).toMatchObject({ ticker: "AAPL", quantity: "10", totalValue: 1000 });
    expect(portfolio.summary.totalValue).toBe(1000);
  });

  it("handles the empty-holdings case (a save that removed everything)", async () => {
    const portfolio = await buildPortfolioResponseFromWireHoldings(
      { portfolioId: "p1", ownerId: "user-001", version: 9 },
      [],
      TOKEN,
    );

    expect(portfolio.holdings).toEqual([]);
    expect(portfolio.summary.totalValue).toBe(0);
    expect(portfolio.summary.bestPerformer.ticker).toBe("N/A");
  });
});
