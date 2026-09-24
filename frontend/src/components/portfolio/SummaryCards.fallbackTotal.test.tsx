/**
 * Rehearsal defect #4, fallback path: when the backend summary has no total, Portfolio Total
 * comes from the client-assembled portfolio, which has no FX step and so counts USD-priced
 * holdings only. It must say it is partial rather than present that as the whole portfolio.
 */
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SummaryCards } from "./SummaryCards";

let backendSummaryTotal: number | null;
let fallbackPartial: boolean | undefined;

vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => ({
    data: {
      summary: {
        totalValue: 1000,
        partialValuation: fallbackPartial,
        totalUnrealizedPnLPercent: 0,
        change24hAbsolute: 0,
        change24hPercent: 0,
        bestPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 0 },
        worstPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 0 },
      },
    },
    isLoading: false,
    isError: false,
  }),
  usePortfolioSummary: () => ({
    data: backendSummaryTotal == null ? undefined : { userId: "u", portfolioCount: 1, totalHoldings: 2, totalValue: backendSummaryTotal },
    isFetching: false,
  }),
  usePortfolioAnalytics: () => ({ data: undefined, isLoading: false }),
}));

beforeEach(() => {
  backendSummaryTotal = null;
  fallbackPartial = undefined;
});

describe("SummaryCards — client-assembled total (analytics and summary unavailable)", () => {
  it("labels a partial fallback total", () => {
    fallbackPartial = true;
    render(<SummaryCards />);

    expect(screen.getByTestId("total-value")).toHaveTextContent("$1,000.00");
    expect(screen.getByTestId("total-value-partial")).toHaveTextContent("Partial: USD-priced holdings only");
  });

  it("shows no label when the fallback total covers every holding", () => {
    fallbackPartial = false;
    render(<SummaryCards />);

    expect(screen.queryByTestId("total-value-partial")).not.toBeInTheDocument();
  });

  it("shows no label when the backend summary supplies the (FX-converted) total", () => {
    fallbackPartial = true;
    backendSummaryTotal = 284531.42;
    render(<SummaryCards />);

    expect(screen.getByTestId("total-value")).toHaveTextContent("$284,531.42");
    expect(screen.queryByTestId("total-value-partial")).not.toBeInTheDocument();
  });
});
