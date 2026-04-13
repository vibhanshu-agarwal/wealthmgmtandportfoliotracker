import { render, screen, fireEvent } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import type { MarketSummaryResponse } from "@/types/insights";

// Recharts ResponsiveContainer requires ResizeObserver which jsdom doesn't provide
beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

// ── Mock setup ────────────────────────────────────────────────────────────────

const mockRefetch = vi.fn();

const mockUseMarketSummary = vi.fn<
  () => {
    data: MarketSummaryResponse | undefined;
    isLoading: boolean;
    isError: boolean;
    refetch: () => void;
  }
>();

vi.mock("@/lib/hooks/useInsights", () => ({
  useMarketSummary: () => mockUseMarketSummary(),
}));

// Import after mock setup
const { MarketSummaryGrid } = await import("./MarketSummaryGrid");

// ── Fixtures ──────────────────────────────────────────────────────────────────

const fixtureData: MarketSummaryResponse = {
  AAPL: {
    ticker: "AAPL",
    latestPrice: 178.5,
    priceHistory: [175.0, 176.2, 177.8, 178.5],
    trendPercent: 2.0,
    aiSummary: "AAPL is Bullish.",
  },
  MSFT: {
    ticker: "MSFT",
    latestPrice: 420.0,
    priceHistory: [422.0, 420.0],
    trendPercent: -0.47,
    aiSummary: null,
  },
};

// ── Property 6: MarketSummaryGrid loading/error/empty states ──────────────────

describe("MarketSummaryGrid — Loading state", () => {
  it("renders skeleton cards while loading", () => {
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("market-summary-grid")).not.toBeInTheDocument();
  });
});

describe("MarketSummaryGrid — Error state", () => {
  it("renders error card with retry button on failure", () => {
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-error")).toBeInTheDocument();
    expect(
      screen.getByText("Unable to load market data. Please try again later."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("market-summary-retry")).toBeInTheDocument();
  });

  it("calls refetch when retry button is clicked", () => {
    mockRefetch.mockClear();
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);
    fireEvent.click(screen.getByTestId("market-summary-retry"));

    expect(mockRefetch).toHaveBeenCalledOnce();
  });
});

describe("MarketSummaryGrid — Empty state", () => {
  it("renders empty message when data is an empty map", () => {
    mockUseMarketSummary.mockReturnValue({
      data: {},
      isLoading: false,
      isError: false,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-empty")).toBeInTheDocument();
    expect(
      screen.getByText("No market data available yet."),
    ).toBeInTheDocument();
  });
});

describe("MarketSummaryGrid — Data state", () => {
  it("renders one MarketSummaryCard per ticker", () => {
    mockUseMarketSummary.mockReturnValue({
      data: fixtureData,
      isLoading: false,
      isError: false,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-grid")).toBeInTheDocument();
    expect(screen.getByText("AAPL")).toBeInTheDocument();
    expect(screen.getByText("MSFT")).toBeInTheDocument();
  });
});
