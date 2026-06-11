/**
 * Task 9.9 — PerformanceChart Wave 5 tests.
 *
 * Requirements validated:
 * - R2 AC7: performance series labelled partial/unavailable per coverage metadata
 * - R8 AC3: synthetic series NOT rendered as real portfolio data
 * - Task 9.6: partial coverage shows indicator with holdings count
 */

import { render, screen } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { PerformanceChart } from "./PerformanceChart";

// ── Recharts mock — avoids SVG rendering in jsdom ─────────────────────────────

vi.mock("recharts", () => ({
  AreaChart: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="area-chart">{children}</div>
  ),
  Area: () => null,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="responsive-container">{children}</div>
  ),
  linearGradient: () => null,
  stop: () => null,
  defs: () => null,
}));

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockUsePortfolioAnalytics = vi.fn();
const mockUsePortfolioPerformance = vi.fn();

vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
  usePortfolioPerformance: () => mockUsePortfolioPerformance(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const today = new Date().toISOString().split("T")[0];
const daysAgo = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().split("T")[0];
};

const realSeries = Array.from({ length: 7 }, (_, i) => ({
  date: daysAgo(6 - i),
  value: 44000 + i * 600,
  change: i === 0 ? 0 : 600,
})).concat([{ date: today, value: 48250.0, change: 650.0 }]);

const analyticsComplete = {
  data: {
    totalValue: 48250.0,
    totalCostBasis: 44000.0,
    totalUnrealizedPnL: 4250.0,
    totalUnrealizedPnLPercent: 9.66,
    baseCurrency: "USD",
    partialValuation: false,
    bestPerformer: { ticker: "AAPL", change24hPercent: 5.26 },
    worstPerformer: { ticker: "BTC", change24hPercent: -2.14 },
    holdings: [],
    performanceSeries: realSeries,
    performanceCoverage: {
      holdingsWithHistory: 10,
      totalHoldings: 10,
      partial: false,
      synthetic: false,
    },
  },
  isLoading: false,
};

const analyticsPartial = {
  data: {
    ...analyticsComplete.data,
    performanceCoverage: {
      holdingsWithHistory: 6,
      totalHoldings: 10,
      partial: true,
      synthetic: false,
    },
  },
  isLoading: false,
};

const analyticsSynthetic = {
  data: {
    ...analyticsComplete.data,
    performanceSeries: [{ date: today, value: 48250.0, change: 0 }],
    performanceCoverage: {
      holdingsWithHistory: 0,
      totalHoldings: 10,
      partial: true,
      synthetic: true,
    },
  },
  isLoading: false,
};

const noAnalytics = { data: undefined, isLoading: false };

const performanceFallback = {
  data: {
    portfolioId: "p1",
    periodDays: 30,
    dataPoints: realSeries,
    periodReturn: 4250.0,
    periodReturnPercent: 9.66,
  },
  isLoading: false,
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("PerformanceChart — Task 9.6: partial coverage labelling", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsePortfolioPerformance.mockReturnValue(performanceFallback);
  });

  it("renders chart without partial label when coverage is complete", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsComplete);
    render(<PerformanceChart />);

    expect(screen.queryByText(/Partial/i)).not.toBeInTheDocument();
    expect(screen.getByTestId("area-chart")).toBeInTheDocument();
  });

  it("shows partial coverage indicator with holdings count when partial=true", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsPartial);
    render(<PerformanceChart />);

    // Should contain "Partial (6/10 holdings)" or similar
    expect(screen.getByText(/Partial \(6\/10 holdings\)/i)).toBeInTheDocument();
  });

  it("renders chart with Estimated label when series is synthetic but backend points exist", () => {
    mockUsePortfolioAnalytics.mockReturnValue({
      data: {
        ...analyticsComplete.data,
        performanceSeries: realSeries,
        performanceCoverage: {
          holdingsWithHistory: 6,
          totalHoldings: 10,
          partial: true,
          synthetic: true,
        },
      },
      isLoading: false,
    });
    render(<PerformanceChart />);

    expect(screen.getByTestId("area-chart")).toBeInTheDocument();
    expect(screen.getByText(/Estimated/i)).toBeInTheDocument();
    expect(
      screen.queryByText("No performance data available yet."),
    ).not.toBeInTheDocument();
  });

  it("hides the chart when no backend or fallback series exists", () => {
    mockUsePortfolioAnalytics.mockReturnValue({
      data: {
        ...analyticsSynthetic.data,
        performanceSeries: [],
      },
      isLoading: false,
    });
    mockUsePortfolioPerformance.mockReturnValue({ data: undefined, isLoading: false });
    render(<PerformanceChart />);

    expect(
      screen.getByText("No performance data available yet."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  it("renders chart from analytics series when analytics is available and non-synthetic", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsComplete);
    render(<PerformanceChart />);

    expect(screen.getByTestId("area-chart")).toBeInTheDocument();
    expect(
      screen.queryByText("No performance data available yet."),
    ).not.toBeInTheDocument();
  });

  it("renders unavailable state when both analytics and performance are empty", () => {
    mockUsePortfolioAnalytics.mockReturnValue(noAnalytics);
    mockUsePortfolioPerformance.mockReturnValue({ data: undefined, isLoading: false });
    render(<PerformanceChart />);

    expect(
      screen.getByText("No performance data available yet."),
    ).toBeInTheDocument();
  });

  it("shows loading skeleton while analytics is loading", () => {
    mockUsePortfolioAnalytics.mockReturnValue({ data: undefined, isLoading: true });
    mockUsePortfolioPerformance.mockReturnValue(performanceFallback);
    render(<PerformanceChart />);

    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  it("renders period return in chart description", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsComplete);
    render(<PerformanceChart />);

    // Description mentions day count
    expect(screen.getByText(/day return/i)).toBeInTheDocument();
  });
});
