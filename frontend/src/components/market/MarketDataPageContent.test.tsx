import { render, screen } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { MarketDataPageContent } from "./MarketDataPageContent";
import type { AssetHoldingDTO } from "@/types/portfolio";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

const mockUseSession = vi.fn();
vi.mock("@/lib/auth-client", () => ({
  useSession: () => mockUseSession(),
}));

const mockUsePortfolio = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const authenticatedSession = {
  data: { user: { id: "u1", name: "Test User", email: "test@example.com" } },
  isPending: false,
};
const pendingSession = { data: null, isPending: true };
const unauthenticatedSession = { data: null, isPending: false };

const sampleHoldings: AssetHoldingDTO[] = [
  {
    id: "h1",
    ticker: "AAPL",
    name: "Apple Inc.",
    assetClass: "STOCK",
    quantity: 10,
    currentPrice: 178.5,
    totalValue: 1785,
    avgCostBasis: 150,
    unrealizedPnL: 285,
    change24hPercent: 1.25,
    change24hAbsolute: 2.2,
    portfolioWeight: 60,
    lastUpdatedAt: "2026-04-10T12:00:00Z",
  },
  {
    id: "h2",
    ticker: "BTC",
    name: "Bitcoin",
    assetClass: "CRYPTO",
    quantity: 0.5,
    currentPrice: 65000,
    totalValue: 32500,
    avgCostBasis: 60000,
    unrealizedPnL: 2500,
    change24hPercent: -2.1,
    change24hAbsolute: -1400,
    portfolioWeight: 40,
    lastUpdatedAt: "2026-04-10T14:30:00Z",
  },
];

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

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("MarketDataPageContent", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default: authenticated + data loaded
    mockUseSession.mockReturnValue(authenticatedSession);
    mockUsePortfolio.mockReturnValue(portfolioWithData);
  });

  // ── Session gate ──────────────────────────────────────────────────────────

  it("renders skeleton when session is pending", () => {
    mockUseSession.mockReturnValue(pendingSession);
    const { container } = render(<MarketDataPageContent />);
    expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
  });

  it("redirects to /login when unauthenticated", () => {
    mockUseSession.mockReturnValue(unauthenticatedSession);
    render(<MarketDataPageContent />);
    expect(mockReplace).toHaveBeenCalledWith("/login");
  });

  it("renders nothing after redirect when unauthenticated", () => {
    mockUseSession.mockReturnValue(unauthenticatedSession);
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

  it("applies green styling for positive change24hPercent", () => {
    render(<MarketDataPageContent />);
    // AAPL has +1.25%
    const aaplRow = screen.getByText("AAPL").closest("tr")!;
    const changeCell = aaplRow.querySelector(".text-green-600");
    expect(changeCell).toBeInTheDocument();
  });

  it("applies red styling for negative change24hPercent", () => {
    render(<MarketDataPageContent />);
    // BTC has -2.1%
    const btcRow = screen.getByText("BTC").closest("tr")!;
    const changeCell = btcRow.querySelector(".text-red-600");
    expect(changeCell).toBeInTheDocument();
  });
});
