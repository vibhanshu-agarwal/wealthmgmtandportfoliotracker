"use client";

import { useState } from "react";
import { TrendingUp, TrendingDown } from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { classifyChangePercent, formatQuotePrice, isKnownCurrency } from "@/lib/utils/format";
import { usePortfolioAnalytics } from "@/lib/hooks/usePortfolio";
import { useMarketSummary } from "@/lib/hooks/useInsights";
import type { HoldingAnalyticsDTO } from "@/types/portfolio";
import type { TickerSummary } from "@/types/insights";

// ── Types ─────────────────────────────────────────────────────────────────────

interface TickerItem {
  label: string;
  value: number;
  /** ISO code `value` is quoted in; null when the source did not say (no "$" is assumed). */
  currency: string | null;
  change: number | null; // null = unavailable
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function TickerCell({ item }: { item: TickerItem }) {
  const changeSign = classifyChangePercent(item.change);

  return (
    <span className="inline-flex items-center gap-2 px-6 whitespace-nowrap">
      <span className="text-xs font-semibold text-white/60 uppercase tracking-wider">
        {item.label}
      </span>
      <span
        className="text-xs font-mono font-semibold text-white tabular-nums"
        title={isKnownCurrency(item.currency) ? undefined : "Currency unavailable"}
      >
        {formatQuotePrice(item.value, item.currency)}
      </span>
      {changeSign === "unavailable" ? (
        <span className="text-xs text-white/30 tabular-nums">—</span>
      ) : changeSign === "neutral" ? (
        <span className="inline-flex items-center gap-0.5 text-xs font-semibold tabular-nums text-white/50">
          0.00%
        </span>
      ) : (
        <span
          className={cn(
            "inline-flex items-center gap-0.5 text-xs font-semibold tabular-nums",
            changeSign === "positive" ? "text-profit" : "text-loss",
          )}
        >
          {changeSign === "positive" ? (
            <TrendingUp className="h-3 w-3" />
          ) : (
            <TrendingDown className="h-3 w-3" />
          )}
          {changeSign === "positive" ? "+" : ""}
          {item.change!.toFixed(2)}%
        </span>
      )}
    </span>
  );
}

// ── Data builders ─────────────────────────────────────────────────────────────

/** Descending by FX-converted value; a holding whose value is unavailable sorts last. */
function compareByValueDesc(a: HoldingAnalyticsDTO, b: HoldingAnalyticsDTO): number {
  if (a.currentValueBase == null) return b.currentValueBase == null ? 0 : 1;
  if (b.currentValueBase == null) return -1;
  return b.currentValueBase - a.currentValueBase;
}

/**
 * Build ticker items from analytics holdings — top 8 by FX-converted value.
 * A holding without a current price is omitted: the strip shows prices, and inventing
 * $0.00 would misstate it (finding F1). Uses real change24hPercent (or null when unavailable).
 */
function buildTickerItemsFromAnalytics(
  holdings: HoldingAnalyticsDTO[],
): TickerItem[] {
  return holdings
    .filter((h) => h.currentPrice != null)
    .sort(compareByValueDesc)
    .slice(0, 8)
    .map((h) => ({
      label: h.ticker,
      value: h.currentPrice as number,
      currency: h.quoteCurrency ?? null,
      change: h.change24hPercent ?? null,
    }));
}

/**
 * Build ticker items from insight market summary (trend = insight trend, not 24h price change).
 * Entries without a latest price are omitted.
 */
function buildTickerItemsFromInsights(
  summary: Record<string, TickerSummary>,
): TickerItem[] {
  return Object.values(summary)
    .filter((s) => s.latestPrice != null)
    .slice(0, 8)
    .map((s) => ({
      label: s.ticker,
      value: s.latestPrice as number,
      currency: s.quoteCurrency ?? null,
      change: s.trendPercent ?? null,
    }));
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * Horizontally scrolling ticker strip showing real portfolio/market data.
 *
 * Task 9.7: MOCK_TICKER removed. Prefers analytics holdings that have a current
 * price (by FX-converted value); falls back to insight market summary when there
 * are none, including when every holding is unpriced. Hides gracefully when no
 * real data is available — never shows mock financial values.
 */
export function PortfolioTicker() {
  const [isPaused, setIsPaused] = useState(false);
  const { data: analytics } = usePortfolioAnalytics();
  const { data: marketSummary } = useMarketSummary();

  // Build ticker items from real data; prefer priced analytics holdings
  let items: TickerItem[] = analytics?.holdings
    ? buildTickerItemsFromAnalytics(analytics.holdings)
    : [];

  if (items.length === 0 && marketSummary) {
    items = buildTickerItemsFromInsights(marketSummary);
  }

  // No real data available — hide the ticker entirely rather than show mock values.
  // R8 AC1: if real data not wired, hide the component.
  if (items.length === 0) {
    return null;
  }

  return (
    <div
      className="relative flex-1 overflow-hidden"
      onMouseEnter={() => setIsPaused(true)}
      onMouseLeave={() => setIsPaused(false)}
      aria-label="Market ticker"
    >
      {/* Left fade */}
      <div className="absolute inset-y-0 left-0 w-8 z-10 bg-gradient-to-r from-sidebar to-transparent pointer-events-none" />
      {/* Right fade */}
      <div className="absolute inset-y-0 right-0 w-8 z-10 bg-gradient-to-l from-sidebar to-transparent pointer-events-none" />

      <div
        className={cn(
          "flex w-max",
          isPaused ? "animation-pause" : "animate-ticker-scroll",
        )}
        style={isPaused ? { animationPlayState: "paused" } : undefined}
      >
        {/* Duplicate for seamless loop */}
        {[...items, ...items].map((item, i) => (
          <TickerCell key={i} item={item} />
        ))}
      </div>
    </div>
  );
}
