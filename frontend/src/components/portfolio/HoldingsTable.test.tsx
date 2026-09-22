/**
 * F1 — a holding without a current price (analytics currentPrice / currentValueBase null,
 * enriched holding currentPrice / totalValue null) must render as unavailable, never as
 * an invented $0.00, and must not distort totals or sorting.
 */

import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { HoldingsTable } from "./HoldingsTable";
import type { AssetHoldingDTO, HoldingAnalyticsDTO } from "@/types/portfolio";

const mockUsePortfolio = vi.fn();
const mockUsePortfolioAnalytics = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
}));

function holding(ticker: string, currentPrice: number | null, totalValue: number | null, portfolioWeight: number): AssetHoldingDTO {
  return {
    id: `id-${ticker}`,
    ticker,
    name: ticker,
    assetClass: ticker === "AAPL" ? "STOCK" : "CRYPTO",
    quantity: "1",
    currentPrice,
    totalValue,
    avgCostBasis: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hPercent: null,
    change24hAbsolute: null,
    portfolioWeight,
    lastUpdatedAt: "2026-09-22T00:00:00Z",
  };
}

function analyticsHolding(ticker: string, currentPrice: number | null, currentValueBase: number | null): HoldingAnalyticsDTO {
  return {
    ticker,
    quantity: 1,
    currentPrice,
    currentValueBase,
    avgCostBasis: null,
    costBasisCurrency: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hAbsolute: null,
    change24hPercent: null,
    change24hReferenceAt: null,
    changeBasis: null,
    quoteCurrency: currentPrice == null ? null : "USD",
    displayAssetClass: ticker === "AAPL" ? "STOCK" : "CRYPTO",
  };
}

const TICKERS = /^(AAPL|BTC-USD|SOL-USD)/;

function rowOrder(): string[] {
  const body = screen.getAllByRole("rowgroup")[1];
  return within(body)
    .getAllByRole("row")
    .map((row) => TICKERS.exec(row.querySelector("td")?.textContent ?? "")?.[1] ?? "?");
}

function cellsOf(ticker: string): HTMLTableCellElement[] {
  const row = screen.getByText(ticker, { selector: "span" }).closest("tr")!;
  return Array.from(row.querySelectorAll("td"));
}

describe("HoldingsTable — F1: unpriced holding", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsePortfolio.mockReturnValue({
      data: {
        portfolioId: "p1",
        ownerId: "u1",
        holdings: [holding("AAPL", 200, 2000, 6.25), holding("BTC-USD", 60000, 30000, 93.75), holding("SOL-USD", null, null, 0)],
      },
      isLoading: false,
    });
    mockUsePortfolioAnalytics.mockReturnValue({
      data: {
        totalValue: 32000,
        holdings: [analyticsHolding("AAPL", 200, 2000), analyticsHolding("BTC-USD", 60000, 30000), analyticsHolding("SOL-USD", null, null)],
      },
      isLoading: false,
    });
  });

  it("renders the unpriced holding's price and value as unavailable, with no weight", () => {
    render(<HoldingsTable />);
    const [, , price, value] = cellsOf("SOL-USD");
    expect(price.textContent).toBe("—");
    expect(value.textContent).toBe("—");
    const row = price.closest("tr")!;
    expect(within(row).queryByText("$0.00")).not.toBeInTheDocument();
    expect(within(row).queryByText(/%$/)).not.toBeInTheDocument();
  });

  it("keeps the priced holdings' values and excludes the unpriced one from the total", () => {
    render(<HoldingsTable />);
    expect(cellsOf("AAPL")[3].textContent).toContain("$2,000.00");
    expect(cellsOf("BTC-USD")[3].textContent).toContain("$30,000.00");
    expect(screen.getByText("$32,000.00")).toBeInTheDocument();
  });

  it("sorts a holding with no 24h change last, in both directions", () => {
    mockUsePortfolioAnalytics.mockReturnValue({
      data: {
        totalValue: 32000,
        holdings: [
          { ...analyticsHolding("AAPL", 200, 2000), change24hPercent: 1.5, change24hAbsolute: 3 },
          { ...analyticsHolding("BTC-USD", 60000, 30000), change24hPercent: -2, change24hAbsolute: -1200 },
          analyticsHolding("SOL-USD", null, null),
        ],
      },
      isLoading: false,
    });
    render(<HoldingsTable />);
    fireEvent.click(screen.getByRole("button", { name: /^24h Change/ }));
    expect(rowOrder()).toEqual(["AAPL", "BTC-USD", "SOL-USD"]);
    fireEvent.click(screen.getByRole("button", { name: /^24h Change/ }));
    expect(rowOrder()).toEqual(["BTC-USD", "AAPL", "SOL-USD"]);
  });

  it("sorts a holding with no unrealised P&L last, in both directions", () => {
    mockUsePortfolioAnalytics.mockReturnValue({
      data: {
        totalValue: 32000,
        holdings: [
          { ...analyticsHolding("AAPL", 200, 2000), unrealizedPnL: 150 },
          { ...analyticsHolding("BTC-USD", 60000, 30000), unrealizedPnL: -400 },
          analyticsHolding("SOL-USD", null, null),
        ],
      },
      isLoading: false,
    });
    render(<HoldingsTable />);
    fireEvent.click(screen.getByRole("button", { name: /^Unr\. P&L/ }));
    expect(rowOrder()).toEqual(["AAPL", "BTC-USD", "SOL-USD"]);
    fireEvent.click(screen.getByRole("button", { name: /^Unr\. P&L/ }));
    expect(rowOrder()).toEqual(["BTC-USD", "AAPL", "SOL-USD"]);
  });

  it("sorts the unpriced holding last by value and by price, in both directions", () => {
    render(<HoldingsTable />);
    expect(rowOrder()).toEqual(["BTC-USD", "AAPL", "SOL-USD"]);
    fireEvent.click(screen.getByRole("button", { name: /^Value/ }));
    expect(rowOrder()).toEqual(["AAPL", "BTC-USD", "SOL-USD"]);
    fireEvent.click(screen.getByRole("button", { name: /^Price/ }));
    expect(rowOrder()).toEqual(["BTC-USD", "AAPL", "SOL-USD"]);
    fireEvent.click(screen.getByRole("button", { name: /^Price/ }));
    expect(rowOrder()).toEqual(["AAPL", "BTC-USD", "SOL-USD"]);
  });
});

// A matching analytics record is authoritative for value and weight, including its nulls:
// the enriched quantity × quote-currency price is neither FX-converted nor part of the
// analytics total the Overview shows (review M1).
describe("HoldingsTable — analytics null value with an enriched price", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  function setup(unvalued: HoldingAnalyticsDTO, enriched: AssetHoldingDTO) {
    mockUsePortfolio.mockReturnValue({
      data: { portfolioId: "p1", ownerId: "u1", holdings: [holding("AAPL", 200, 2000, 7.5), enriched] },
      isLoading: false,
    });
    mockUsePortfolioAnalytics.mockReturnValue({
      data: { totalValue: 2000, holdings: [analyticsHolding("AAPL", 200, 2000), unvalued] },
      isLoading: false,
    });
    render(<HoldingsTable />);
  }

  it("FX unavailable: shows the quote price but no value or weight, and keeps it out of the total", () => {
    setup(
      { ...analyticsHolding("SOL-USD", 1242.3, null), quoteCurrency: "INR" },
      holding("SOL-USD", 1242.3, 24846, 92.5),
    );
    const [, , price, value] = cellsOf("SOL-USD");
    expect(price.textContent).toBe("$1,242.30");
    expect(value.textContent).toBe("—");
    expect(within(value.closest("tr")!).queryByText(/%$/)).not.toBeInTheDocument();
    expect(cellsOf("AAPL")[3].textContent).toBe("$2,000.00100.0%");
    expect(screen.getByText("Total (2 assets)").parentElement!.textContent).toContain("$2,000.00");
  });

  it("price missing from portfolio-service only: keeps the market-data price, but no value", () => {
    setup(analyticsHolding("SOL-USD", null, null), holding("SOL-USD", 12.9, 64.5, 3.1));
    const [, , price, value] = cellsOf("SOL-USD");
    expect(price.textContent).toBe("$12.90");
    expect(value.textContent).toBe("—");
    expect(screen.getByText("Total (2 assets)").parentElement!.textContent).toContain("$2,000.00");
  });
});

// D11 (finding F13): change24hAbsolute is a per-unit, quote-currency price change. The row sub-line
// and the footer show the position's base-currency change, so the rows add up to the footer.
describe("HoldingsTable — D11: position-level 24h values", () => {
  // 12 AAPL up 126.48 each → +1,517.76; 0.5 BTC down 2,000 each → −1,000.00.
  // SOL-USD's percent is known, but it has no FX rate, so neither its value nor its 24h value is.
  const aapl: HoldingAnalyticsDTO = {
    ...analyticsHolding("AAPL", 1126.48, 13517.76),
    quantity: 12,
    change24hPercent: 12.648,
    change24hAbsolute: 126.48,
    change24hValueBase: 1517.76,
  };
  const btc: HoldingAnalyticsDTO = {
    ...analyticsHolding("BTC-USD", 60000, 30000),
    quantity: 0.5,
    change24hPercent: -3.2258,
    change24hAbsolute: -2000,
    change24hValueBase: -1000,
  };
  const sol: HoldingAnalyticsDTO = {
    ...analyticsHolding("SOL-USD", 150, null),
    change24hPercent: 1.5,
    change24hAbsolute: 2.25,
    change24hValueBase: null,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  function setup(analyticsHoldings: HoldingAnalyticsDTO[]) {
    mockUsePortfolio.mockReturnValue({
      data: {
        portfolioId: "p1",
        ownerId: "u1",
        holdings: [holding("AAPL", 1126.48, 13517.76, 31), holding("BTC-USD", 60000, 30000, 69), holding("SOL-USD", 150, 6000, 0)],
      },
      isLoading: false,
    });
    mockUsePortfolioAnalytics.mockReturnValue({
      data: { totalValue: 43517.76, holdings: analyticsHoldings },
      isLoading: false,
    });
    render(<HoldingsTable />);
  }

  function cell24h(ticker: string): HTMLTableCellElement {
    const cells = cellsOf(ticker);
    return cells[cells.length - 1];
  }

  function footer24h(): string | null {
    return screen.getByText("24h", { selector: "p" }).nextElementSibling!.textContent;
  }

  it("shows each row's position-level change under its percent", () => {
    setup([aapl, btc, sol]);
    expect(cell24h("AAPL").textContent).toBe("+12.65%+$1,517.76");
    expect(cell24h("BTC-USD").textContent).toBe("-3.23%-$1,000.00");
  });

  it("shows the percent without a sub-line when the position value is unavailable", () => {
    setup([aapl, btc, sol]);
    expect(cell24h("SOL-USD").textContent).toBe("+1.50%");
  });

  it("totals the position-level changes in the footer", () => {
    // 1,517.76 − 1,000.00 = +517.76; SOL-USD has no base-currency change and is left out.
    setup([aapl, btc, sol]);
    expect(footer24h()).toBe("+$517.76");
  });

  it('shows "—" in the footer when no row has a position-level change (an older backend)', () => {
    const older = [aapl, btc, sol].map((h) => {
      const copy: HoldingAnalyticsDTO = { ...h };
      delete copy.change24hValueBase;
      return copy;
    });
    setup(older);
    expect(footer24h()).toBe("—");
  });
});
