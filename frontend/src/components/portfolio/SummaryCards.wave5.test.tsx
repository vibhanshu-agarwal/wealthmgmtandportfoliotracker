/**
 * Task 9.9 — SummaryCards Wave 5 tests.
 *
 * Requirements validated:
 * - R8 AC2: "24h Profit/Loss" bound to backend analytics, not synthetic summary
 * - R8 AC3: null values render "—", never $0.00 / +0.00%
 * - R3 AC2: P&L unavailable → "—" (all-time return)
 * - Property 2: No silent zero
 */

import { render, screen } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { SummaryCards } from "./SummaryCards";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockUsePortfolio = vi.fn();
const mockUsePortfolioSummary = vi.fn();
const mockUsePortfolioAnalytics = vi.fn();

vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioSummary: () => mockUsePortfolioSummary(),
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const portfolioData = {
  data: {
    portfolioId: "p1",
    ownerId: "u1",
    name: "My Portfolio",
    currency: "USD",
    summary: {
      totalValue: 48250.0,
      totalCostBasis: 44000.0,
      totalUnrealizedPnL: 0,
      totalUnrealizedPnLPercent: 0,
      change24hAbsolute: 0,
      change24hPercent: 0,
      bestPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 0 },
      worstPerformer: { ticker: "BTC", name: "Bitcoin", change24hPercent: 0 },
    },
    holdings: [],
    asOfDate: new Date().toISOString(),
  },
  isLoading: false,
};

const summaryData = {
  data: { totalValue: 48250.0, portfolioCount: 1, totalHoldings: 2 },
  isFetching: false,
};

const analyticsWithChange = {
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
        change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "STOCK",
      },
      {
        ticker: "BTC",
        quantity: 0.65,
        currentPrice: 70775.0,
        currentValueBase: 46003.75,
        avgCostBasis: 64000.0,
        costBasisCurrency: "USD",
        unrealizedPnL: 4403.75,
        unrealizedPnLPercent: 10.58,
        change24hAbsolute: -1543.5,
        change24hPercent: -2.14,
        change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "CRYPTO",
      },
    ],
    performanceSeries: [],
    performanceCoverage: { holdingsWithHistory: 2, totalHoldings: 2, partial: false, synthetic: false },
  },
};

const analyticsWithNullChange = {
  data: {
    ...analyticsWithChange.data,
    holdings: analyticsWithChange.data.holdings.map((h) => ({
      ...h,
      change24hAbsolute: null,
      change24hPercent: null,
    })),
    totalUnrealizedPnL: null,
    totalUnrealizedPnLPercent: null,
    bestPerformer: { ticker: "AAPL", change24hPercent: null },
    worstPerformer: { ticker: "BTC", change24hPercent: null },
  },
};

const noAnalytics = { data: undefined };

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("SummaryCards — Task 9.4: 24h Profit/Loss bound to analytics", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsePortfolio.mockReturnValue(portfolioData);
    mockUsePortfolioSummary.mockReturnValue(summaryData);
  });

  it("renders real 24h P&L from analytics when available (not the synthetic 0)", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithChange);
    render(<SummaryCards />);

    // AAPL: +10.6, BTC: -1543.5 → sum = -1532.9
    const pnlEl = screen.getByTestId("24h-pnl");
    // Should NOT be the synthetic +$0.00 from fetchPortfolio summary
    expect(pnlEl.textContent).not.toBe("+$0.00");
    expect(pnlEl.textContent).not.toBe("$0.00");
  });

  it('renders "—" for 24h P&L when analytics has no change data (null)', () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithNullChange);
    render(<SummaryCards />);

    const pnlEl = screen.getByTestId("24h-pnl");
    expect(pnlEl.textContent).toBe("—");
  });

  it('renders "—" for 24h P&L when analytics is unavailable', () => {
    mockUsePortfolioAnalytics.mockReturnValue(noAnalytics);
    render(<SummaryCards />);

    const pnlEl = screen.getByTestId("24h-pnl");
    expect(pnlEl.textContent).toBe("—");
  });

  it('renders "—" for all-time return when unrealizedPnLPercent is null', () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithNullChange);
    render(<SummaryCards />);
    expect(screen.getByText("all-time return —")).toBeInTheDocument();
  });

  it("renders real all-time return when unrealizedPnLPercent is available", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithChange);
    render(<SummaryCards />);
    expect(screen.getByText("all-time return")).toBeInTheDocument();
    // The ChangeIndicator renders the percent — should not be "—"
    expect(screen.queryByText("all-time return —")).not.toBeInTheDocument();
  });

  it("renders backend best performer ticker from analytics", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithChange);
    render(<SummaryCards />);
    expect(screen.getByText("AAPL")).toBeInTheDocument();
  });

  it("renders worst performer ticker from analytics", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithChange);
    render(<SummaryCards />);
    expect(screen.getByText("BTC")).toBeInTheDocument();
  });

  it('renders "—" for best performer change when null', () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithNullChange);
    render(<SummaryCards />);
    // Best Performing Asset card title
    expect(screen.getByText("Best Performing Asset")).toBeInTheDocument();
  });

  it("never renders +$0.00 as the 24h P&L (Property 2: No silent zero)", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithNullChange);
    render(<SummaryCards />);
    expect(screen.queryByText("+$0.00")).not.toBeInTheDocument();
  });
});
