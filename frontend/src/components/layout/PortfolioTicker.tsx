"use client";

import { useState } from "react";
import { TrendingUp, TrendingDown } from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { usePortfolioAnalytics } from "@/lib/hooks/usePortfolio";
import { useMarketSummary } from "@/lib/hooks/useInsights";
import type { HoldingAnalyticsDTO } from "@/types/portfolio";
import type { TickerSummary } from "@/types/insights";

// ── Types ─────────────────────────────────────────────────────────────────────

interface TickerItem {
  label: string;
  value: number;
  change: number | null; // null = unavailable
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatValue(value: number): string {
  if (value >= 1_000) {
    return value.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }
  return value.toFixed(2);
}

function TickerCell({ item }: { item: TickerItem }) {
  const isPositive = (item.change ?? 0) >= 0;
  const hasChange = item.change != null;

  return (
    <span className="inline-flex items-center gap-2 px-6 whitespace-nowrap">
      <span className="text-xs font-semibold text-white/60 uppercase tracking-wider">
        {item.label}
      </span>
      <span className="text-xs font-mono font-semibold text-white tabular-nums">
        ${formatValue(item.value)}
      </span>
      {hasChange ? (
        <span
          className={cn(
            "inline-flex items-center gap-0.5 text-xs font-semibold tabular-nums",
            isPositive ? "text-profit" : "text-loss",
          )}
        >
          {isPositive ? (
            <TrendingUp className="h-3 w-3" />
          ) : (
            <TrendingDown className="h-3 w-3" />
          )}
          {isPositive ? "+" : ""}
          {item.change!.toFixed(2)}%
        </span>
      ) : (
        <span className="text-xs text-white/30 tabular-nums">—</span>
      )}
    </span>
  );
}

// ── Data builders ─────────────────────────────────────────────────────────────

/**
 * Build ticker items from analytics holdings — top 8 by FX-converted value.
 * Uses real change24hPercent (or null when unavailable).
 */
function buildTickerItemsFromAnalytics(
  holdings: HoldingAnalyticsDTO[],
): TickerItem[] {
  return [...holdings]
    .sort((a, b) => b.currentValueBase - a.currentValueBase)
    .slice(0, 8)
    .map((h) => ({
      label: h.ticker,
      value: h.currentPrice,
      change: h.change24hPercent ?? null,
    }));
}

/**
 * Build ticker items from insight market summary (trend = insight trend, not 24h price change).
 */
function buildTickerItemsFromInsights(
  summary: Record<string, TickerSummary>,
): TickerItem[] {
  return Object.values(summary)
    .slice(0, 8)
    .map((s) => ({
      label: s.ticker,
      value: s.latestPrice,
      change: s.trendPercent ?? null,
    }));
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * Horizontally scrolling ticker strip showing real portfolio/market data.
 *
 * Task 9.7: MOCK_TICKER removed. Prefers analytics holdings (by FX-converted
 * value); falls back to insight market summary. Hides gracefully when no real
 * data is available — never shows mock financial values.
 */
export function PortfolioTicker() {
  const [isPaused, setIsPaused] = useState(false);
  const { data: analytics } = usePortfolioAnalytics();
  const { data: marketSummary } = useMarketSummary();

  // Build ticker items from real data; prefer analytics holdings
  let items: TickerItem[] = [];

  if (analytics?.holdings && analytics.holdings.length > 0) {
    items = buildTickerItemsFromAnalytics(analytics.holdings);
  } else if (marketSummary && Object.keys(marketSummary).length > 0) {
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
