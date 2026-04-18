import { render, screen } from "@testing-library/react";
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
});
