"use client";

import { Activity, Star, TrendingDown, TrendingUp, Wallet } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import {
  usePortfolio,
  usePortfolioAnalytics,
  usePortfolioSummary,
} from "@/lib/hooks/usePortfolio";
import {
  formatCurrency,
  formatPercent,
  formatSignedCurrency,
} from "@/lib/utils/format";
import { cn } from "@/lib/utils/cn";
import React from "react";

// ── Individual card components ────────────────────────────────────────────────

function StatCard({
  title,
  icon: Icon,
  children,
  className,
}: {
  title: string;
  icon: React.ElementType;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <Card className={cn("relative overflow-hidden", className)}>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {title}
        </CardTitle>
        <div className="flex h-8 w-8 items-center justify-center rounded-md bg-muted">
          <Icon className="h-4 w-4 text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

function ChangeIndicator({
  value,
  showSign = true,
  size = "sm",
}: {
  value: number;
  showSign?: boolean;
  size?: "sm" | "lg";
}) {
  const isPositive = value >= 0;
  const Icon = isPositive ? TrendingUp : TrendingDown;

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 font-semibold tabular-nums",
        isPositive ? "text-profit" : "text-loss",
        size === "lg" ? "text-base" : "text-xs",
      )}
    >
      <Icon className={size === "lg" ? "h-4 w-4" : "h-3 w-3"} />
      {showSign && (isPositive ? "+" : "")}
      {formatPercent(value).replace("+", "")}
    </span>
  );
}

// ── Skeleton state ────────────────────────────────────────────────────────────

function SummaryCardsSkeleton() {
  return (
    <>
      {[0, 1, 2].map((i) => (
        <Card key={i}>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-8 w-8 rounded-md" />
          </CardHeader>
          <CardContent className="space-y-2">
            <Skeleton className="h-8 w-40" />
            <Skeleton className="h-4 w-24" />
          </CardContent>
        </Card>
      ))}
    </>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export function SummaryCards() {
  const { data: portfolio, isLoading: isPortfolioLoading } = usePortfolio();
  const { data: portfolioSummary, isFetching: isSummaryFetching } =
    usePortfolioSummary();
  const { data: analytics } = usePortfolioAnalytics();

  // Skeleton only while we have neither summary nor the main portfolio payload yet.
  // If /summary is slow but /portfolio returned, still render total-value from the
  // client-computed portfolio.summary so E2E and users are not stuck on a blank row.
  if (
    isSummaryFetching &&
    !portfolioSummary &&
    (isPortfolioLoading || !portfolio)
  ) {
    return <SummaryCardsSkeleton />;
  }

  // If portfolio fetch failed, render cards with zero/placeholder values
  // rather than a hard error — backend may simply be unavailable in local dev.
  const summary = portfolio?.summary ?? {
    totalValue: 0,
    totalCostBasis: 0,
    totalUnrealizedPnL: 0,
    totalUnrealizedPnLPercent: 0,
    change24hAbsolute: 0,
    change24hPercent: 0,
    bestPerformer: { ticker: "—", name: "No data", change24hPercent: 0 },
    worstPerformer: { ticker: "—", name: "No data", change24hPercent: 0 },
  };
  const pnlIsPositive = summary.change24hAbsolute >= 0;

  // Prefer backend aggregate from /api/portfolio/summary (accurate SQL join with
  // market prices) over the frontend-computed value which depends on the
  // market-data-service being available.
  const portfolioTotal =
    portfolioSummary?.totalValue != null &&
    Number(portfolioSummary.totalValue) > 0
      ? Number(portfolioSummary.totalValue)
      : (portfolio?.summary.totalValue ?? 0);

  // Use backend-computed performers when available; fall back to placeholder from fetchPortfolio.
  const bestPerformer = analytics?.bestPerformer ?? summary.bestPerformer;
  const worstPerformer = analytics?.worstPerformer ?? summary.worstPerformer;
  // Use backend-computed unrealized P&L percent when available.
  const unrealizedPnLPercent =
    analytics?.totalUnrealizedPnLPercent ?? summary.totalUnrealizedPnLPercent;

  return (
    <>
      {/* ── Card 1: Portfolio Total ── */}
      <StatCard title="Portfolio Total" icon={Wallet}>
        <div className="space-y-1">
          <p
            className="text-3xl font-bold tracking-tight tabular-nums"
            data-testid="total-value"
          >
            {formatCurrency(portfolioTotal)}
          </p>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <ChangeIndicator value={unrealizedPnLPercent} />
            <span>all-time return</span>
          </div>
        </div>
        {/* Subtle gradient accent */}
        <div className="absolute -right-4 -top-4 h-20 w-20 rounded-full bg-profit/5 blur-xl" />
      </StatCard>

      {/* ── Card 2: 24h Profit / Loss ── */}
      <StatCard
        title="24h Profit / Loss"
        icon={Activity}
        className={pnlIsPositive ? "border-profit/20" : "border-loss/20"}
      >
        <div className="space-y-1">
          <p
            className={cn(
              "text-3xl font-bold tracking-tight tabular-nums",
              pnlIsPositive ? "text-profit" : "text-loss",
            )}
          >
            {formatSignedCurrency(summary.change24hAbsolute)}
          </p>
          <div className="flex items-center gap-2">
            <ChangeIndicator value={summary.change24hPercent} />
            <span className="text-xs text-muted-foreground">
              since yesterday
            </span>
          </div>
        </div>
        <div
          className={cn(
            "absolute -right-4 -top-4 h-20 w-20 rounded-full blur-xl",
            pnlIsPositive ? "bg-profit/8" : "bg-loss/8",
          )}
        />
      </StatCard>

      {/* ── Card 3: Best Performing Asset ── */}
      <StatCard title="Best Performing Asset" icon={Star}>
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <Badge
              variant="secondary"
              className="font-mono text-sm font-bold px-2"
            >
              {bestPerformer.ticker}
            </Badge>
            <ChangeIndicator value={bestPerformer.change24hPercent} size="lg" />
          </div>
          {"name" in bestPerformer && (
            <p className="text-xs text-muted-foreground">
              {(bestPerformer as { name: string }).name}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            Worst:{" "}
            <span className="font-mono font-semibold text-foreground">
              {worstPerformer.ticker}
            </span>{" "}
            <span className="text-loss">
              {formatPercent(worstPerformer.change24hPercent)}
            </span>
          </p>
        </div>
        <div className="absolute -right-4 -top-4 h-20 w-20 rounded-full bg-profit/5 blur-xl" />
      </StatCard>
    </>
  );
}
