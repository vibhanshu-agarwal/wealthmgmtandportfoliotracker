import { render, screen, within } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { MarketDataPageContent } from "./MarketDataPageContent";
import type { AssetHoldingDTO, HoldingAnalyticsDTO } from "@/types/portfolio";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

const mockUseAuthSession = vi.fn();
vi.mock("@/lib/auth/session", () => ({
  useAuthSession: () => mockUseAuthSession(),
}));

const mockUsePortfolio = vi.fn();
const mockUsePortfolioAnalytics = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const authenticatedSession = {
  data: {
    userId: "u1",
    token: "jwt-token",
    name: "Test User",
    email: "test@example.com",
  },
  isPending: false,
};
const pendingSession = { data: null, isPending: true };
const unauthenticatedSession = { data: null, isPending: false };

// Base holdings as fetchPortfolio produces them: the 24h fields are always null
// placeholders there; the real values only come from the analytics response.
const sampleHoldings: AssetHoldingDTO[] = [
  {
    id: "h1",
    ticker: "AAPL",
    name: "Apple Inc.",
    assetClass: "STOCK",
    quantity: "10",
    currentPrice: 178.5,
    totalValue: 1785,
    avgCostBasis: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hPercent: null,
    change24hAbsolute: null,
    portfolioWeight: 60,
    lastUpdatedAt: "2026-04-10T12:00:00Z",
  },
  {
    id: "h2",
    ticker: "BTC",
    name: "Bitcoin",
    assetClass: "CRYPTO",
    quantity: "0.5",
    currentPrice: 65000,
    totalValue: 32500,
    avgCostBasis: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hPercent: null,
    change24hAbsolute: null,
    portfolioWeight: 40,
    lastUpdatedAt: "2026-04-10T14:30:00Z",
  },
];

function holdingAnalytics(
  ticker: string,
  change24hPercent: number | null,
  change24hAbsolute: number | null,
): HoldingAnalyticsDTO {
  return {
    ticker,
    quantity: 1,
    currentPrice: 1,
    currentValueBase: 1,
    avgCostBasis: null,
    costBasisCurrency: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hAbsolute,
    change24hPercent,
    change24hReferenceAt: change24hPercent == null ? null : "2026-04-09T14:30:00Z",
    changeBasis: change24hPercent == null ? null : "WITHIN_24H_WINDOW",
    quoteCurrency: "USD",
    displayAssetClass: "STOCK",
  };
}

function analyticsResult(holdings: HoldingAnalyticsDTO[]) {
  return { data: { holdings }, isLoading: false, isError: false };
}

const analyticsWithChanges = analyticsResult([
  holdingAnalytics("AAPL", 1.25, 2.2),
  holdingAnalytics("BTC", -2.1, -1400),
]);
const analyticsLoading = { data: undefined, isLoading: true, isError: false };
const analyticsError = { data: undefined, isLoading: false, isError: true };

const portfolioWithData = {
  data: {
    portfolioId: "p1",
    ownerId: "u1",
    name: "My Portfolio",
    currency: "USD",
    summary: {} as never,
    holdings: sampleHoldings,
    asOfDate: "2026-04-10T14:30:00Z",
  },
  isLoading: false,
  isError: false,
};

const portfolioLoading = { data: undefined, isLoading: true, isError: false };
const portfolioEmpty = {
  data: { holdings: [] },
  isLoading: false,
  isError: false,
};
const portfolioError = { data: undefined, isLoading: false, isError: true };

/** The 24h Change cell is the third column. */
function changeCellFor(ticker: string): HTMLTableCellElement {
  const row = screen.getByText(ticker).closest("tr")!;
  return row.querySelectorAll("td")[2] as HTMLTableCellElement;
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("MarketDataPageContent", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default: authenticated + portfolio and analytics loaded
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUsePortfolio.mockReturnValue(portfolioWithData);
    mockUsePortfolioAnalytics.mockReturnValue(analyticsWithChanges);
  });

  // ── Session gate ──────────────────────────────────────────────────────────

  it("renders skeleton when session is pending", () => {
    mockUseAuthSession.mockReturnValue(pendingSession);
    const { container } = render(<MarketDataPageContent />);
    expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
  });

  it("redirects to /login when unauthenticated", () => {
    mockUseAuthSession.mockReturnValue(unauthenticatedSession);
    render(<MarketDataPageContent />);
    expect(mockReplace).toHaveBeenCalledWith("/login");
  });

  it("renders nothing after redirect when unauthenticated", () => {
    mockUseAuthSession.mockReturnValue(unauthenticatedSession);
    const { container } = render(<MarketDataPageContent />);
    expect(container.innerHTML).toBe("");
  });

  // ── Loading state ─────────────────────────────────────────────────────────

  it("renders table skeleton when portfolio is loading", () => {
    mockUsePortfolio.mockReturnValue(portfolioLoading);
    render(<MarketDataPageContent />);
    expect(screen.getByText("Market Prices")).toBeInTheDocument();
    expect(
      screen.getByText("Current prices and 24-hour changes for your holdings"),
    ).toBeInTheDocument();
  });

  // ── Empty state ───────────────────────────────────────────────────────────

  it('renders "No market data" fallback when holdings array is empty', () => {
    mockUsePortfolio.mockReturnValue(portfolioEmpty);
    render(<MarketDataPageContent />);
    expect(screen.getByText("No market data available.")).toBeInTheDocument();
  });

  // ── Error state ───────────────────────────────────────────────────────────

  it("renders error fallback when usePortfolio returns an error", () => {
    mockUsePortfolio.mockReturnValue(portfolioError);
    render(<MarketDataPageContent />);
    expect(
      screen.getByText("Unable to load market data. Please try again later."),
    ).toBeInTheDocument();
  });

  // ── Data state ────────────────────────────────────────────────────────────

  it("renders table with correct column headers", () => {
    render(<MarketDataPageContent />);
    expect(screen.getByText("Ticker")).toBeInTheDocument();
    expect(screen.getByText("Current Price")).toBeInTheDocument();
    expect(screen.getByText("24h Change")).toBeInTheDocument();
    expect(screen.getByText("Last Updated")).toBeInTheDocument();
  });

  it("renders correct number of rows matching holdings count", () => {
    render(<MarketDataPageContent />);
    const rows = screen.getAllByRole("row");
    // 1 header row + 2 data rows
    expect(rows).toHaveLength(3);
  });

  // ── 24h change from analytics ─────────────────────────────────────────────

  it("shows the analytics 24h change in place of the base holding's null placeholder", () => {
    render(<MarketDataPageContent />);
    const cell = changeCellFor("AAPL");
    expect(cell.textContent).toContain("+1.25%");
    expect(cell.textContent).toContain("+$2.20");
    expect(cell.textContent).not.toContain("—");
  });

  it("applies green styling for a positive analytics change", () => {
    render(<MarketDataPageContent />);
    const cell = changeCellFor("AAPL");
    // A dash cell also carries the green class, so require the value as well.
    expect(cell.textContent).toContain("+1.25%");
    expect(cell.className).toContain("text-green-600");
  });

  it("applies red styling and a signed amount for a negative analytics change", () => {
    render(<MarketDataPageContent />);
    const cell = changeCellFor("BTC");
    expect(cell.className).toContain("text-red-600");
    expect(cell.textContent).toContain("-2.10%");
    expect(cell.textContent).toContain("-$1,400.00");
  });

  it("renders a dash when analytics has the holding but no 24h reference", () => {
    mockUsePortfolioAnalytics.mockReturnValue(
      analyticsResult([
        holdingAnalytics("AAPL", null, null),
        holdingAnalytics("BTC", -2.1, -1400),
      ]),
    );
    render(<MarketDataPageContent />);
    expect(changeCellFor("AAPL").textContent).toBe("—");
    expect(changeCellFor("BTC").textContent).toContain("-2.10%");
  });

  it("renders a dash for a holding with no matching analytics record", () => {
    mockUsePortfolioAnalytics.mockReturnValue(
      analyticsResult([holdingAnalytics("BTC", -2.1, -1400)]),
    );
    render(<MarketDataPageContent />);
    expect(changeCellFor("AAPL").textContent).toBe("—");
    expect(changeCellFor("BTC").textContent).toContain("-2.10%");
  });

  it("does not invent a $0.00 amount when only the percentage is present", () => {
    mockUsePortfolioAnalytics.mockReturnValue(
      analyticsResult([
        holdingAnalytics("AAPL", 1.25, null),
        holdingAnalytics("BTC", -2.1, -1400),
      ]),
    );
    render(<MarketDataPageContent />);
    const cell = changeCellFor("AAPL");
    expect(cell.textContent).toContain("+1.25%");
    expect(cell.textContent).not.toContain("$0.00");
  });

  it("keeps price rows visible while analytics is still loading", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsLoading);
    render(<MarketDataPageContent />);

    expect(screen.getByText("$178.50")).toBeInTheDocument();
    expect(screen.getByText("$65,000.00")).toBeInTheDocument();
    // Pending is not "unavailable": show a placeholder, not a dash.
    const cell = changeCellFor("AAPL");
    expect(within(cell).getByTestId("change24h-loading")).toBeInTheDocument();
    expect(cell.textContent).not.toContain("—");
  });

  it("keeps price rows visible and shows dashes when analytics fails", () => {
    mockUsePortfolioAnalytics.mockReturnValue(analyticsError);
    render(<MarketDataPageContent />);

    expect(screen.getByText("$178.50")).toBeInTheDocument();
    expect(screen.getByText("$65,000.00")).toBeInTheDocument();
    expect(changeCellFor("AAPL").textContent).toBe("—");
    expect(changeCellFor("BTC").textContent).toBe("—");
  });
});
