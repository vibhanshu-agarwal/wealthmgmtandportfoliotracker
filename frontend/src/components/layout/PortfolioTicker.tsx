"use client";

import { useState } from "react";
import { TrendingUp, TrendingDown } from "lucide-react";
import { cn } from "@/lib/utils/cn";

interface TickerItem {
  label: string;
  value: number;
  change: number; // percentage
}

const MOCK_TICKER: TickerItem[] = [
  { label: "Portfolio", value: 284_531.42, change: 1.24 },
  { label: "AAPL", value: 189.72, change: 0.83 },
  { label: "BTC", value: 67_420.5, change: -2.14 },
  { label: "MSFT", value: 415.3, change: 1.56 },
  { label: "S&P 500", value: 5_304.12, change: 0.42 },
  { label: "GOOGL", value: 173.55, change: -0.31 },
  { label: "NVDA", value: 875.4, change: 3.21 },
  { label: "Gold", value: 2_340.8, change: 0.17 },
];

function formatValue(value: number): string {
  if (value >= 1_000) {
    return value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  return value.toFixed(2);
}

function TickerCell({ item }: { item: TickerItem }) {
  const isPositive = item.change >= 0;
  return (
    <span className="inline-flex items-center gap-2 px-6 whitespace-nowrap">
      <span className="text-xs font-semibold text-white/60 uppercase tracking-wider">
        {item.label}
      </span>
      <span className="text-xs font-mono font-semibold text-white tabular-nums">
        ${formatValue(item.value)}
      </span>
      <span
        className={cn(
          "inline-flex items-center gap-0.5 text-xs font-semibold tabular-nums",
          isPositive ? "text-profit" : "text-loss"
        )}
      >
        {isPositive ? (
          <TrendingUp className="h-3 w-3" />
        ) : (
          <TrendingDown className="h-3 w-3" />
        )}
        {isPositive ? "+" : ""}
        {item.change.toFixed(2)}%
      </span>
    </span>
  );
}

/**
 * Horizontally scrolling ticker strip showing mock portfolio + market data.
 * Duplicates the list to create a seamless loop effect.
 */
export function PortfolioTicker() {
  const [isPaused, setIsPaused] = useState(false);

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
          isPaused ? "animation-pause" : "animate-ticker-scroll"
        )}
        style={isPaused ? { animationPlayState: "paused" } : undefined}
      >
        {/* Duplicate for seamless loop */}
        {[...MOCK_TICKER, ...MOCK_TICKER].map((item, i) => (
          <TickerCell key={i} item={item} />
        ))}
      </div>
    </div>
  );
}
