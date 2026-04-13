"use client";

import { RefreshCw } from "lucide-react";
import { useMarketSummary } from "@/lib/hooks/useInsights";
import { MarketSummaryCard } from "./MarketSummaryCard";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";

// ── Skeleton state ────────────────────────────────────────────────────────────

function GridSkeleton() {
  return (
    <div
      className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
      data-testid="market-summary-skeleton"
    >
      {[0, 1, 2].map((i) => (
        <Card key={i}>
          <CardContent className="space-y-3 p-6">
            <div className="flex items-center justify-between">
              <Skeleton className="h-4 w-16" />
              <Skeleton className="h-4 w-20" />
            </div>
            <Skeleton className="h-8 w-28" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-5 w-40" />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

/**
 * Client component that owns the TanStack Query data fetching for market
 * summaries and renders a responsive grid of MarketSummaryCard components.
 */
export function MarketSummaryGrid() {
  const { data, isLoading, isError, refetch } = useMarketSummary();

  if (isLoading) {
    return <GridSkeleton />;
  }

  if (isError) {
    return (
      <Card data-testid="market-summary-error">
        <CardContent className="flex flex-col items-center gap-3 p-6 text-center">
          <p className="text-sm text-muted-foreground">
            Unable to load market data. Please try again later.
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => refetch()}
            data-testid="market-summary-retry"
          >
            <RefreshCw className="mr-2 h-3.5 w-3.5" />
            Retry
          </Button>
        </CardContent>
      </Card>
    );
  }

  const entries = data ? Object.values(data) : [];

  if (entries.length === 0) {
    return (
      <Card data-testid="market-summary-empty">
        <CardContent className="p-6 text-center">
          <p className="text-sm text-muted-foreground">
            No market data available yet.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <div
      className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
      data-testid="market-summary-grid"
    >
      {entries.map((summary) => (
        <MarketSummaryCard key={summary.ticker} summary={summary} />
      ))}
    </div>
  );
}
