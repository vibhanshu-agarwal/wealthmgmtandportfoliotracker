/**
 * B2 Task 1.4 — `EditHoldingsButton` is wired onto the Portfolio page behind
 * `NEXT_PUBLIC_ENABLE_ASSET_PICKER`, defaulting to absent when the flag is unset.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
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
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioSummary: () => ({ data: undefined, isLoading: false }),
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

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioPageContent />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: "Edit Holdings" })).toBeInTheDocument();
  });
});
