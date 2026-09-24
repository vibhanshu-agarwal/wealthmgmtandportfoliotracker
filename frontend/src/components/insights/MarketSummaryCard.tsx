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
import {
  classifyChangePercent,
  formatPercent,
} from "@/lib/utils/format";
import type { SentimentSource, TickerSummary } from "@/types/insights";
import { sentimentSourceLabel } from "@/lib/utils/sentimentSource";
import { DemoDataBadge } from "@/components/ui/DemoDataBadge";
import { QuotePrice } from "@/components/ui/QuotePrice";

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
  // trendPercent is first-to-last over the stored price window, not a 24-hour change
  // (rehearsal defect #5); the card says which window.
  const windowSize = (priceHistory || []).length;

  // Trend direction
  const safeTrendPercent = trendPercent ?? null;
  const trendSign = classifyChangePercent(safeTrendPercent);

  // Sparkline stroke color follows trend direction
  const sparklineColor =
    trendSign === "positive"
      ? "hsl(160 84% 39%)"
      : trendSign === "negative"
        ? "hsl(0 72% 51%)"
        : "hsl(215 16% 47%)";

  // Convert priceHistory to Recharts data format
  const safePriceHistory = priceHistory || [];
  const sparklineData = safePriceHistory.map((price, i) => ({ i, price }));

  return (
    <Card className="relative overflow-hidden">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-mono font-bold">{ticker}</CardTitle>
        <div className="inline-flex items-center gap-1.5">
          <DemoDataBadge
            description={
              <>
                Showing seeded demo data. The change shown is first-to-last over the
                last {windowSize} stored prices, not a 24-hour change.
              </>
            }
          />
          <TrendIndicator trendPercent={safeTrendPercent} trendSign={trendSign} />
        </div>
      </CardHeader>

      <CardContent className="space-y-3">
        {/* Price */}
        <p className="text-2xl font-bold tracking-tight tabular-nums">
          {latestPrice == null ? (
            <span className="text-muted-foreground">—</span>
          ) : (
            <QuotePrice value={latestPrice} currency={summary.quoteCurrency} />
          )}
        </p>

        {/* Sparkline — hidden when fewer than 2 data points */}
        {safePriceHistory.length >= 2 && (
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
        {trendSign !== "unavailable" && (
          <p className="text-xs text-muted-foreground" data-testid="trend-window">
            Change over last {windowSize} prices
          </p>
        )}
        <SentimentSection aiSummary={aiSummary} source={summary.aiSummarySource} />
      </CardContent>
    </Card>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function TrendIndicator({
  trendPercent,
  trendSign,
}: {
  trendPercent: number | null;
  trendSign: ReturnType<typeof classifyChangePercent>;
}) {
  if (trendSign === "unavailable") {
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

  if (trendSign === "neutral") {
    return (
      <span
        className="inline-flex items-center gap-1 text-sm font-semibold tabular-nums text-muted-foreground"
        data-testid="trend-neutral"
      >
        <Minus className="h-4 w-4" />
        {formatPercent(0)}
      </span>
    );
  }

  const isPositive = trendSign === "positive";
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

function SentimentSection({
  aiSummary,
  source,
}: {
  aiSummary: string | null;
  source?: SentimentSource | null;
}) {
  if (aiSummary) {
    const sourceLabel = sentimentSourceLabel(source);
    return (
      <div className="space-y-1">
        <Badge
          variant="secondary"
          className="text-xs font-normal max-w-full"
          title={aiSummary}
          data-testid="sentiment-badge"
        >
          {/* text-overflow has no effect on the inline-flex badge; truncate a shrinkable child. */}
          <span className="min-w-0 truncate" data-testid="sentiment-text">
            {aiSummary}
          </span>
        </Badge>
        {sourceLabel && (
          <p className="text-[10px] text-muted-foreground" data-testid="sentiment-source">
            {sourceLabel}
          </p>
        )}
      </div>
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
