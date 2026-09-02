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
  () => { data: PortfolioSummaryDTO | undefined; isLoading: boolean }
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

describe("PortfolioPageContent — freshness status (Task 1.16/1.18)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  it("renders the freshness status from usePortfolioSummary's assetPriceFreshness", () => {
    mockUseAuthSession.mockReturnValue(authenticatedSession);
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "u1",
      token: "jwt-token",
      status: "authenticated",
      error: null,
    });
    stubPortfolio();
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

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );

    expect(screen.getByText(/1 holding stale/i)).toBeInTheDocument();
  });
});
