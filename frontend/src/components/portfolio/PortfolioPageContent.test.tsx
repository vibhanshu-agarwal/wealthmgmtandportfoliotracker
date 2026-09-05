/**
 * B2 Task 1.4 — `EditHoldingsButton` is wired onto the Portfolio page behind
 * `NEXT_PUBLIC_ENABLE_ASSET_PICKER`, defaulting to absent when the flag is unset.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { PortfolioSummaryDTO } from "../../../types/portfolio";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PortfolioPageContent } from "./PortfolioPageContent";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: mockReplace }) }));

const mockUseAuthSession = vi.fn();
vi.mock("@/lib/auth/session", () => ({ useAuthSession: () => mockUseAuthSession() }));

const mockUseAuthenticatedUserId = vi.fn();
vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => mockUseAuthenticatedUserId(),
}));

const mockUsePortfolio = vi.fn();
const mockUsePortfolioSummary = vi.fn<
  () => {
    data: PortfolioSummaryDTO | undefined;
    isLoading: boolean;
    isError?: boolean;
    error?: Error | null;
  }
>(() => ({ data: undefined, isLoading: false }));
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioSummary: () => mockUsePortfolioSummary(),
  usePortfolioAnalytics: () => ({ data: undefined, isLoading: false }),
}));

vi.mock("@/components/portfolio/SummaryCards", () => ({ SummaryCards: () => <div /> }));
vi.mock("@/components/charts/PerformanceChart", () => ({ PerformanceChart: () => <div /> }));
vi.mock("@/components/charts/AllocationChart", () => ({ AllocationChart: () => <div /> }));
vi.mock("@/components/portfolio/HoldingsTable", () => ({ HoldingsTable: () => <div /> }));

const authenticatedSession = {
  data: { userId: "u1", token: "jwt-token", name: "Test", email: "t@example.com" },
  isPending: false,
};

function stubPortfolio() {
  mockUsePortfolio.mockReturnValue({
    data: {
      portfolioId: "p1",
      ownerId: "u1",
      name: "My Portfolio",
      currency: "USD",
      version: 4,
      summary: {},
      holdings: [{ id: "h1", ticker: "AAPL", quantity: "10" }],
      asOfDate: new Date().toISOString(),
    },
    isLoading: false,
  });
}

function stubNetwork() {
  server.use(
    http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
    http.get("/api/market/prices", () => HttpResponse.json([])),
    http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
  );
}

describe("PortfolioPageContent — Asset Picker entry point", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  it("does not render Edit Holdings when the flag is unset (default disabled)", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", undefined);
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.queryByRole("button", { name: "Edit Holdings" })).not.toBeInTheDocument();
  });

  it("renders Edit Holdings when the flag is exactly \"true\"", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", "true");
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();
    stubNetwork();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: "Edit Holdings" })).toBeInTheDocument();
  });
});

describe("PortfolioPageContent — manual reset control (Tasks 6.1/6.2, independent flag)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  it("does not render the reset control when its flag is unset (default disabled)", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", undefined);
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.queryByRole("button", { name: /reset demo portfolio/i })).not.toBeInTheDocument();
  });

  it("renders the reset control when its flag is exactly \"true\", independently of the picker flag", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", "true");
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", undefined);
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Holdings" })).not.toBeInTheDocument();
  });

  it("disables the reset control when the portfolio's version was not genuinely observed", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", "true");
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    mockUsePortfolio.mockReturnValue({
      data: {
        portfolioId: "p1",
        ownerId: "u1",
        name: "My Portfolio",
        currency: "USD",
        version: 0,
        versionObserved: false,
        summary: {},
        holdings: [],
        asOfDate: new Date().toISOString(),
      },
      isLoading: false,
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeDisabled();
  });

  it("renders Edit Holdings without the reset control when only the picker flag is set", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", "true");
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", undefined);
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();
    stubNetwork();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: "Edit Holdings" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /reset demo portfolio/i })).not.toBeInTheDocument();
  });

  it("renders both controls independently when both flags are set", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", "true");
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", "true");
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();
    stubNetwork();

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: "Edit Holdings" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeInTheDocument();
  });
});

describe("PortfolioPageContent — freshness status (Task 1.16/1.18 / 9.5)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  function renderAuthenticatedPage() {
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();
    stubNetwork();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
  }

  it("renders the freshness status from usePortfolioSummary's assetPriceFreshness", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: {
        userId: "u1",
        portfolioCount: 1,
        totalHoldings: 1,
        totalValue: 1000,
        assetPriceFreshness: {
          state: "STALE",
          staleHoldings: 1,
          unknownPriceHoldings: 0,
          missingPriceHoldings: 0,
        },
      },
      isLoading: false,
    });

    renderAuthenticatedPage();

    expect(screen.getByText(/1 holding stale/i)).toBeInTheDocument();
  });

  it("renders the backend-selected MISSING state with mixed nonzero counts and a known timestamp", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: {
        userId: "u1",
        portfolioCount: 1,
        totalHoldings: 6,
        totalValue: 1000,
        assetPriceFreshness: {
          state: "MISSING",
          oldestKnownAssetPriceObservationTimestamp: "2026-08-14T08:00:12Z",
          staleHoldings: 1,
          unknownPriceHoldings: 2,
          missingPriceHoldings: 3,
        },
      },
      isLoading: false,
    });

    renderAuthenticatedPage();

    // Compact strip follows backend state (MISSING), not a client-side sum of counts.
    expect(screen.getByText(/3 holdings missing/i)).toBeInTheDocument();
    expect(screen.getByText(/prices as of/i)).toBeInTheDocument();
    expect(screen.queryByText(/all prices fresh/i)).not.toBeInTheDocument();
  });

  it("shows the absent-timestamp copy when oldestKnownAssetPriceObservationTimestamp is omitted", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: {
        userId: "u1",
        portfolioCount: 1,
        totalHoldings: 0,
        totalValue: 0,
        assetPriceFreshness: {
          state: "MISSING",
          staleHoldings: 0,
          unknownPriceHoldings: 0,
          missingPriceHoldings: 2,
        },
      },
      isLoading: false,
    });

    renderAuthenticatedPage();

    expect(screen.getByText(/no price observation on record/i)).toBeInTheDocument();
    expect(screen.getByText(/2 holdings missing/i)).toBeInTheDocument();
  });

  it("follows assetPriceFreshness even when holdings would contradict that state", () => {
    // Holdings look priced/complete; freshness object deliberately says STALE.
    mockUsePortfolio.mockReturnValue({
      data: {
        portfolioId: "p1",
        ownerId: "u1",
        name: "My Portfolio",
        currency: "USD",
        version: 4,
        summary: { partialValuation: false },
        holdings: [
          { id: "h1", ticker: "AAPL", quantity: "10", currentPrice: 190 },
          { id: "h2", ticker: "MSFT", quantity: "5", currentPrice: 400 },
        ],
        asOfDate: new Date().toISOString(),
      },
      isLoading: false,
    });
    mockUsePortfolioSummary.mockReturnValue({
      data: {
        userId: "u1",
        portfolioCount: 1,
        totalHoldings: 2,
        totalValue: 3900,
        assetPriceFreshness: {
          state: "STALE",
          oldestKnownAssetPriceObservationTimestamp: "2026-08-01T00:00:00Z",
          staleHoldings: 2,
          unknownPriceHoldings: 0,
          missingPriceHoldings: 0,
        },
      },
      isLoading: false,
    });

    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubNetwork();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );

    expect(screen.getByText(/2 holdings stale/i)).toBeInTheDocument();
    expect(screen.queryByText(/all prices fresh/i)).not.toBeInTheDocument();
  });

  it("does not present known-fresh while summary is loading or missing", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: undefined,
      isLoading: true,
    });

    renderAuthenticatedPage();

    expect(screen.queryByText(/all prices fresh/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/prices as of/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /details/i })).not.toBeInTheDocument();
  });

  it("does not present known-fresh when summary failed or freshness is unusable", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Request failed (500)"),
    });

    renderAuthenticatedPage();

    expect(screen.queryByText(/all prices fresh/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/prices as of/i)).not.toBeInTheDocument();
  });

  it("does not present known-fresh when the summary body omits assetPriceFreshness", () => {
    mockUsePortfolioSummary.mockReturnValue({
      data: {
        userId: "u1",
        portfolioCount: 1,
        totalHoldings: 1,
        totalValue: 1000,
      } as PortfolioSummaryDTO,
      isLoading: false,
    });

    renderAuthenticatedPage();

    expect(screen.queryByText(/all prices fresh/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /details/i })).not.toBeInTheDocument();
  });
});
