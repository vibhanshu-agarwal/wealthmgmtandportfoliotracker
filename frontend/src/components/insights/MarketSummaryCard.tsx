"use client";

import { Info, Minus, TrendingDown, TrendingUp } from "lucide-react";
import { LineChart, Line, ResponsiveContainer } from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils/cn";
import { formatCurrency, formatPercent } from "@/lib/utils/format";
import type { TickerSummary } from "@/types/insights";

interface MarketSummaryCardProps {
  summary: TickerSummary;
}

/**
 * Presentational card for a single ticker's market summary.
 * Displays ticker, latest price, trend indicator, AI sentiment badge,
 * and a sparkline chart from priceHistory.
 */
export function MarketSummaryCard({ summary }: MarketSummaryCardProps) {
  const { ticker, latestPrice, priceHistory, trendPercent, aiSummary } =
    summary;

  // Trend direction
  const trendIsPositive = trendPercent !== null && trendPercent > 0;
  const trendIsNegative = trendPercent !== null && trendPercent < 0;
  const trendIsNull = trendPercent === null;

  // Sparkline stroke color follows trend direction
  const sparklineColor = trendIsPositive
    ? "hsl(160 84% 39%)"
    : trendIsNegative
      ? "hsl(0 72% 51%)"
      : "hsl(215 16% 47%)";

  // Convert priceHistory to Recharts data format
  const sparklineData = priceHistory.map((price, i) => ({ i, price }));

  return (
    <Card className="relative overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-mono font-bold">{ticker}</CardTitle>
        <TrendIndicator
          trendPercent={trendPercent}
          isPositive={trendIsPositive}
          isNull={trendIsNull}
        />
      </CardHeader>

      <CardContent className="space-y-3">
        {/* Price */}
        <p className="text-2xl font-bold tracking-tight tabular-nums">
          {formatCurrency(latestPrice)}
        </p>

        {/* Sparkline — hidden when fewer than 2 data points */}
        {priceHistory.length >= 2 && (
          <div className="h-12" data-testid="sparkline">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={sparklineData}>
                <Line
                  type="monotone"
                  dataKey="price"
                  stroke={sparklineColor}
                  strokeWidth={1.5}
                  dot={false}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}

        {/* Sentiment badge or unavailable tooltip */}
        <SentimentSection aiSummary={aiSummary} />
      </CardContent>
    </Card>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function TrendIndicator({
  trendPercent,
  isPositive,
  isNull,
}: {
  trendPercent: number | null;
  isPositive: boolean;
  isNull: boolean;
}) {
  if (isNull) {
    return (
      <span
        className="inline-flex items-center gap-1 text-sm text-muted-foreground"
        data-testid="trend-null"
      >
        <Minus className="h-4 w-4" />
        <span>—</span>
      </span>
    );
  }

  const Icon = isPositive ? TrendingUp : TrendingDown;

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 text-sm font-semibold tabular-nums",
        isPositive ? "text-profit" : "text-loss",
      )}
      data-testid={isPositive ? "trend-positive" : "trend-negative"}
    >
      <Icon className="h-4 w-4" />
      {formatPercent(trendPercent!)}
    </span>
  );
}

function SentimentSection({ aiSummary }: { aiSummary: string | null }) {
  if (aiSummary) {
    return (
      <Badge
        variant="secondary"
        className="text-xs font-normal truncate max-w-full"
        data-testid="sentiment-badge"
      >
        {aiSummary}
      </Badge>
    );
  }

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            className="inline-flex items-center gap-1 text-xs text-muted-foreground cursor-help"
            data-testid="sentiment-unavailable"
          >
            <Info className="h-3.5 w-3.5" />
            <span className="sr-only">Sentiment Unavailable</span>
          </span>
        </TooltipTrigger>
        <TooltipContent>
          <p>Sentiment Unavailable</p>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
