"use client";

import { RefreshCw } from "lucide-react";
import { useMarketSummary } from "@/lib/hooks/useInsights";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";
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
  const auth = useAuthenticatedUserId();
  const { data, isLoading, isError, refetch } = useMarketSummary();

  if (auth.status === "loading" || isLoading) {
    return <GridSkeleton />;
  }

  if (auth.status === "error") {
    return (
      <Card data-testid="market-summary-auth-error">
        <CardContent className="flex flex-col items-center gap-3 p-6 text-center">
          <p className="text-sm text-muted-foreground">
            Unable to establish an authenticated data session for insights.
          </p>
          <p className="text-xs text-muted-foreground/80">
            {auth.error ?? "JWT exchange failed."}
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => window.location.reload()}
            data-testid="market-summary-auth-reload"
          >
            Reload
          </Button>
        </CardContent>
      </Card>
    );
  }

  if (auth.status === "unauthenticated") {
    return (
      <Card data-testid="market-summary-auth-required">
        <CardContent className="p-6 text-center">
          <p className="text-sm text-muted-foreground">
            Sign in to load AI market summaries.
          </p>
        </CardContent>
      </Card>
    );
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

  const entries = data 
    ? Object.values(data).filter((s) => s && typeof s.ticker === "string") 
    : [];

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
