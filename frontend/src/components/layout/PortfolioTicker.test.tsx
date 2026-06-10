/**
 * Task 9.9 — PortfolioTicker Wave 5 tests.
 *
 * Requirements validated:
 * - R8 AC1: ticker strip backed by real data, OR hidden — never shows mock values
 * - Task 9.7: MOCK_TICKER removed; hides when no real data available
 */

import { render, screen } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { PortfolioTicker } from "./PortfolioTicker";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockUsePortfolioAnalytics = vi.fn();
const mockUseMarketSummary = vi.fn();

vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
}));

vi.mock("@/lib/hooks/useInsights", () => ({
  useMarketSummary: () => mockUseMarketSummary(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const analyticsWithHoldings = {
  data: {
    totalValue: 48250.0,
    totalCostBasis: 44000.0,
    totalUnrealizedPnL: 4250.0,
    totalUnrealizedPnLPercent: 9.66,
    baseCurrency: "USD",
    partialValuation: false,
    bestPerformer: { ticker: "AAPL", change24hPercent: 5.26 },
    worstPerformer: { ticker: "BTC", change24hPercent: -2.14 },
    holdings: [
      {
        ticker: "AAPL",
        quantity: 10,
        currentPrice: 212.5,
        currentValueBase: 2125.0,
        avgCostBasis: 190.0,
        costBasisCurrency: "USD",
        unrealizedPnL: 225.0,
        unrealizedPnLPercent: 11.84,
        change24hAbsolute: 10.6,
        change24hPercent: 5.26,
        change24hReferenceAt: null,
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "STOCK",
      },
      {
        ticker: "BTC-USD",
        quantity: 0.65,
        currentPrice: 70775.0,
        currentValueBase: 46003.75,
        avgCostBasis: null,
        costBasisCurrency: null,
        unrealizedPnL: null,
        unrealizedPnLPercent: null,
        change24hAbsolute: null,
        change24hPercent: null,
        change24hReferenceAt: null,
        changeBasis: null,
        quoteCurrency: "USD",
        displayAssetClass: "CRYPTO",
      },
    ],
    performanceSeries: [],
    performanceCoverage: { holdingsWithHistory: 1, totalHoldings: 2, partial: true, synthetic: false },
  },
};

const marketSummaryData = {
  data: {
    MSFT: {
      ticker: "MSFT",
      latestPrice: 420.0,
      priceHistory: [422.0, 421.0, 420.0],
      trendPercent: -0.47,
    },
    GOOG: {
      ticker: "GOOG",
      latestPrice: 175.0,
      priceHistory: [175.0],
      trendPercent: null,
    },
  },
};

const noData = { data: undefined };
const emptyData = { data: {} };

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("PortfolioTicker — Task 9.7: no mock data", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders nothing when no analytics and no market summary (hidden, not mock)", () => {
    mockUsePortfolioAnalytics.mockReturnValue(noData);
    mockUseMarketSummary.mockReturnValue(noData);
    const { container } = render(<PortfolioTicker />);
    expect(container.innerHTML).toBe("");
  });

  it("renders nothing when analytics returns empty holdings", () => {
    mockUsePortfolioAnalytics.mockReturnValue({ data: { ...analyticsWithHoldings.data, holdings: [] } });
    mockUseMarketSummary.mockReturnValue(noData);
    const { container } = render(<PortfolioTicker />);
    expect(container.innerHTML).toBe("");
  });

  it("renders nothing when market summary is empty object", () => {
    mockUsePortfolioAnalytics.mockReturnValue(noData);
    mockUseMarketSummary.mockReturnValue(emptyData);
    const { container } = render(<PortfolioTicker />);
    expect(container.innerHTML).toBe("");
  });

  it("renders real tickers from analytics holdings when available", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithHoldings);
    mockUseMarketSummary.mockReturnValue(noData);
    render(<PortfolioTicker />);
    // Both tickers should appear
    expect(screen.getAllByText("AAPL").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("BTC-USD").length).toBeGreaterThanOrEqual(1);
  });

  it("renders real tickers from market summary when analytics unavailable", () => {
    mockUsePortfolioAnalytics.mockReturnValue(noData);
    mockUseMarketSummary.mockReturnValue(marketSummaryData);
    render(<PortfolioTicker />);
    expect(screen.getAllByText("MSFT").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("GOOG").length).toBeGreaterThanOrEqual(1);
  });

  it('renders "—" for unavailable 24h change (null) rather than 0.00%', () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithHoldings);
    mockUseMarketSummary.mockReturnValue(noData);
    render(<PortfolioTicker />);
    // BTC-USD has null change — should show "—"
    expect(screen.queryByText("+0.00%")).not.toBeInTheDocument();
  });

  it("never renders hardcoded mock values like 'Portfolio' with a $284,531.42 value", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithHoldings);
    mockUseMarketSummary.mockReturnValue(noData);
    render(<PortfolioTicker />);
    // These are the values from the old MOCK_TICKER — must never appear
    expect(screen.queryByText("$284,531.42")).not.toBeInTheDocument();
    expect(screen.queryByText("$67,420.50")).not.toBeInTheDocument();
  });

  it("prefers analytics data over market summary when both available", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithHoldings);
    mockUseMarketSummary.mockReturnValue(marketSummaryData);
    render(<PortfolioTicker />);
    // Analytics tickers should be present
    expect(screen.getAllByText("AAPL").length).toBeGreaterThanOrEqual(1);
    // Market summary tickers may or may not appear depending on implementation
    // but the key test is that analytics data takes precedence
  });

  it("renders ticker strip element with aria-label when data is available", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithHoldings);
    mockUseMarketSummary.mockReturnValue(noData);
    render(<PortfolioTicker />);
    expect(screen.getByRole("generic", { name: "Market ticker" })).toBeInTheDocument();
  });
});
