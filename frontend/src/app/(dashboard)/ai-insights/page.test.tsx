import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/insights/MarketSummaryGrid", () => ({ MarketSummaryGrid: () => null }));
vi.mock("@/components/insights/ChatInterface", () => ({ ChatInterface: () => null }));

import AIInsightsPage from "./page";

// Rehearsal defect #5: the cards list every ticker with recent prices, not the user's portfolio.
describe("AI Insights page", () => {
  it("does not describe the market summaries as the user's tracked tickers", () => {
    render(<AIInsightsPage />);
    expect(screen.getByText("Market summaries for all tickers with recent prices, and chat.")).toBeInTheDocument();
    expect(screen.queryByText(/your tracked tickers/i)).not.toBeInTheDocument();
  });
});
