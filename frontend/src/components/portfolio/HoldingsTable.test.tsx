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
