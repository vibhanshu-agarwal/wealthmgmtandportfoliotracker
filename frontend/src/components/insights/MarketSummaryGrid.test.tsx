import { render, screen, fireEvent } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import type { MarketSummaryResponse } from "@/types/insights";
import { RateLimitError } from "@/lib/api/fetchWithAuth";

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
    error: Error | null;
    refetch: () => void;
  }
>();
const mockUseAuthenticatedUserId = vi.fn<
  () => {
    userId: string;
    token: string;
    status: "authenticated" | "loading" | "unauthenticated" | "error";
    error: string | null;
  }
>();

vi.mock("@/lib/hooks/useInsights", () => ({
  useMarketSummary: () => mockUseMarketSummary(),
}));
vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => mockUseAuthenticatedUserId(),
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
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "loading",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("market-summary-grid")).not.toBeInTheDocument();
  });
});

describe("MarketSummaryGrid — Error state", () => {
  it("renders error card with retry button on failure", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Request failed (500) for /api/insights/market-summary"),
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
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Request failed (500) for /api/insights/market-summary"),
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);
    fireEvent.click(screen.getByTestId("market-summary-retry"));

    expect(mockRefetch).toHaveBeenCalledOnce();
  });
});

describe("MarketSummaryGrid — Rate-limited state (429)", () => {
  it("renders a distinguishable rate-limit card instead of the generic error card", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new RateLimitError("Rate limit exceeded (429) for /api/insights/market-summary", 6),
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-rate-limited")).toBeInTheDocument();
    expect(screen.queryByTestId("market-summary-error")).not.toBeInTheDocument();
    expect(
      screen.getByText(
        "You're requesting market data too quickly. Please wait a moment and try again.",
      ),
    ).toBeInTheDocument();
  });

  it("still offers a retry action from the rate-limit card", () => {
    mockRefetch.mockClear();
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new RateLimitError("Rate limit exceeded (429)", null),
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);
    fireEvent.click(screen.getByTestId("market-summary-retry"));

    expect(mockRefetch).toHaveBeenCalledOnce();
  });
});

describe("MarketSummaryGrid — Empty state", () => {
  it("renders empty message when data is an empty map", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: {},
      isLoading: false,
      isError: false,
      error: null,
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
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "jwt",
      status: "authenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: fixtureData,
      isLoading: false,
      isError: false,
      error: null,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);

    expect(screen.getByTestId("market-summary-grid")).toBeInTheDocument();
    expect(screen.getByText("AAPL")).toBeInTheDocument();
    expect(screen.getByText("MSFT")).toBeInTheDocument();
  });
});

describe("MarketSummaryGrid — Auth diagnostics", () => {
  it("renders auth exchange error details when JWT exchange fails", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "",
      token: "",
      status: "error",
      error: "JWT exchange failed (503)",
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
      error: null,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);
    expect(screen.getByTestId("market-summary-auth-error")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Unable to establish an authenticated data session for insights.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("JWT exchange failed (503)")).toBeInTheDocument();
  });

  it("renders sign-in hint when user is unauthenticated", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "",
      token: "",
      status: "unauthenticated",
      error: null,
    });
    mockUseMarketSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
      error: null,
      refetch: mockRefetch,
    });

    render(<MarketSummaryGrid />);
    expect(screen.getByTestId("market-summary-auth-required")).toBeInTheDocument();
    expect(
      screen.getByText("Sign in to load AI market summaries."),
    ).toBeInTheDocument();
  });
});
