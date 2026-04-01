import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
import { SummaryCards } from "./SummaryCards";

vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => ({
    data: {
      summary: {
        totalValue: 100000,
        totalUnrealizedPnLPercent: 12.5,
        change24hAbsolute: 2500,
        change24hPercent: 1.2,
        bestPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 1.8 },
        worstPerformer: { ticker: "TSLA", name: "Tesla", change24hPercent: -0.7 },
      },
    },
    isLoading: false,
    isError: false,
  }),
  usePortfolioSummary: () => ({
    data: {
      userId: "user-001",
      portfolioCount: 2,
      totalHoldings: 7,
      totalValue: 284531.42,
    },
    isLoading: false,
  }),
}));

describe("SummaryCards", () => {
  it("renders Portfolio Total from the summary endpoint payload", () => {
    render(<SummaryCards />);

    expect(screen.getByText("Portfolio Total")).toBeInTheDocument();
    expect(screen.getByText("$284,531.42")).toBeInTheDocument();
  });
});
