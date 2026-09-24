import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, describe, expect, it } from "vitest";
import { MarketSummaryCard } from "./MarketSummaryCard";
import type { TickerSummary } from "@/types/insights";

// Recharts ResponsiveContainer requires ResizeObserver which jsdom doesn't provide
beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

// ── Fixtures ──────────────────────────────────────────────────────────────────

const baseSummary: TickerSummary = {
  ticker: "AAPL",
  latestPrice: 178.5,
  quoteCurrency: "USD",
  priceHistory: [175.0, 176.2, 177.8, 178.5],
  trendPercent: 2.0,
  aiSummary: "AAPL is Bullish. Prices are rising.",
};

// ── Property 1: Trend indicator correctness ───────────────────────────────────

describe("MarketSummaryCard — Trend indicator", () => {
  it("renders green upward arrow for positive trendPercent", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, trendPercent: 2.0 }} />,
    );

    expect(screen.getByTestId("trend-positive")).toBeInTheDocument();
    expect(screen.queryByTestId("trend-negative")).not.toBeInTheDocument();
    expect(screen.queryByTestId("trend-null")).not.toBeInTheDocument();
  });

  it("renders red downward arrow for negative trendPercent", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, trendPercent: -1.5 }} />,
    );

    expect(screen.getByTestId("trend-negative")).toBeInTheDocument();
    expect(screen.queryByTestId("trend-positive")).not.toBeInTheDocument();
    expect(screen.queryByTestId("trend-null")).not.toBeInTheDocument();
  });

  it("renders neutral dash for null trendPercent", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, trendPercent: null }} />,
    );

    expect(screen.getByTestId("trend-null")).toBeInTheDocument();
    expect(screen.queryByTestId("trend-positive")).not.toBeInTheDocument();
    expect(screen.queryByTestId("trend-negative")).not.toBeInTheDocument();
  });

  it("renders neutral styling for zero trendPercent (flat, not a loss)", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, trendPercent: 0 }} />,
    );

    expect(screen.getByTestId("trend-neutral")).toBeInTheDocument();
    expect(screen.queryByTestId("trend-negative")).not.toBeInTheDocument();
    expect(screen.queryByTestId("trend-positive")).not.toBeInTheDocument();
  });
});

// ── Property 2: Sentiment badge visibility ────────────────────────────────────

describe("MarketSummaryCard — Sentiment badge", () => {
  it("displays sentiment badge when aiSummary is non-null", () => {
    render(
      <MarketSummaryCard
        summary={{ ...baseSummary, aiSummary: "AAPL is Bullish." }}
      />,
    );

    expect(screen.getByTestId("sentiment-badge")).toBeInTheDocument();
    expect(screen.getByText("AAPL is Bullish.")).toBeInTheDocument();
    expect(
      screen.queryByTestId("sentiment-unavailable"),
    ).not.toBeInTheDocument();
  });

  it("truncates a long summary on an inner text element and keeps the full text available", () => {
    const longSummary =
      "AAPL is Bullish. Prices are rising on strong services revenue, record buybacks and a widening lead in on-device AI.";
    render(
      <MarketSummaryCard summary={{ ...baseSummary, aiSummary: longSummary }} />,
    );

    const badge = screen.getByTestId("sentiment-badge");
    // The badge is inline-flex, where text-overflow has no effect, so the ellipsis must be
    // produced by a shrinkable block-level child rather than by the badge itself.
    expect(badge.className).toContain("max-w-full");
    expect(badge.className).not.toContain("truncate");

    const text = within(badge).getByTestId("sentiment-text");
    expect(text.className).toContain("truncate");
    expect(text.className).toContain("min-w-0");
    expect(text.textContent).toBe(longSummary);

    // Full summary stays reachable when the visible text is cut short.
    expect(badge).toHaveAttribute("title", longSummary);
  });

  it("hides sentiment badge and shows unavailable icon when aiSummary is null", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, aiSummary: null }} />);

    expect(screen.queryByTestId("sentiment-badge")).not.toBeInTheDocument();
    expect(screen.getByTestId("sentiment-unavailable")).toBeInTheDocument();
  });
});

// ── Property 3: Sparkline rendering edge case ─────────────────────────────────

describe("MarketSummaryCard — Sparkline", () => {
  it("renders sparkline when priceHistory has 2 or more points", () => {
    render(
      <MarketSummaryCard
        summary={{ ...baseSummary, priceHistory: [175.0, 176.2, 177.8, 178.5] }}
      />,
    );

    expect(screen.getByTestId("sparkline")).toBeInTheDocument();
  });

  it("hides sparkline when priceHistory has 1 point", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, priceHistory: [178.5] }} />,
    );

    expect(screen.queryByTestId("sparkline")).not.toBeInTheDocument();
  });

  it("hides sparkline when priceHistory is empty", () => {
    render(
      <MarketSummaryCard summary={{ ...baseSummary, priceHistory: [] }} />,
    );

    expect(screen.queryByTestId("sparkline")).not.toBeInTheDocument();
  });
});

// ── Basic rendering ───────────────────────────────────────────────────────────

describe("MarketSummaryCard — Basic rendering", () => {
  it("displays ticker symbol and formatted price", () => {
    render(<MarketSummaryCard summary={baseSummary} />);

    expect(screen.getByText("AAPL")).toBeInTheDocument();
    expect(screen.getByText("$178.50")).toBeInTheDocument();
  });

  // Rehearsal defect #3: the card price is in the ticker's own quote currency.
  it("shows an INR price with the rupee sign", () => {
    render(
      <MarketSummaryCard
        summary={{ ...baseSummary, ticker: "M&M.NS", latestPrice: 3010.1, quoteCurrency: "INR" }}
      />,
    );
    expect(screen.getByText("₹3,010.10")).toBeInTheDocument();
  });

  it("shows the bare number, never $, when the currency is unknown", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, quoteCurrency: null }} />);
    const price = screen.getByText("178.50");
    expect(price).toHaveAttribute("title", "Currency unavailable");
    expect(screen.queryByText("$178.50")).not.toBeInTheDocument();
  });
});

// ── F1 follow-through: the wire's latestPrice is null for a ticker with no data ──

describe("MarketSummaryCard — unavailable latest price", () => {
  it("shows a dash instead of an invented $0.00", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, latestPrice: null }} />);
    expect(screen.queryByText("$0.00")).not.toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });
});

// ── Rehearsal defect #5: the card's change window and its sentiment provenance ──

describe("MarketSummaryCard — change window and sentiment source", () => {
  it("says which window the change covers", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, priceHistory: [175.0, 176.2, 177.8, 178.5], trendPercent: 2.0 }} />);
    expect(screen.getByTestId("trend-window")).toHaveTextContent("Change over last 4 prices");
  });

  it("never describes the card's change as 24h", async () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, trendPercent: 2.0 }} />);
    const badge = screen.getByTestId("demo-data-badge");
    fireEvent.pointerEnter(badge);
    fireEvent.focus(badge);
    await waitFor(() => {
      expect(screen.getByRole("tooltip")).toHaveTextContent(/not a 24-hour change/i);
    });
    expect(screen.getByRole("tooltip").textContent).not.toMatch(/24h %/);
  });

  it("omits the window label when there is no change", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, trendPercent: null, priceHistory: [178.5] }} />);
    expect(screen.queryByTestId("trend-window")).not.toBeInTheDocument();
  });

  it("shows the declared sentiment source under the sentiment", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, aiSummary: "Neutral.", aiSummarySource: "BEDROCK" }} />);
    expect(screen.getByTestId("sentiment-source")).toHaveTextContent("Generated by Amazon Bedrock");
  });

  it("makes no provenance claim when the source is absent", () => {
    render(<MarketSummaryCard summary={{ ...baseSummary, aiSummary: "Neutral.", aiSummarySource: null }} />);
    expect(screen.getByTestId("sentiment-text")).toHaveTextContent("Neutral.");
    expect(screen.queryByTestId("sentiment-source")).not.toBeInTheDocument();
  });
});
