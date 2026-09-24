"use client";

import {
  Activity,
  AlertCircle,
  Minus,
  Star,
  TrendingDown,
  TrendingUp,
  Wallet,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import {
  usePortfolio,
  usePortfolioAnalytics,
  usePortfolioSummary,
} from "@/lib/hooks/usePortfolio";
import {
  classifyChangePercent,
  formatCurrency,
  formatPercent,
  formatSignedCurrencyOrDash,
  formatDate,
} from "@/lib/utils/format";
import { cn } from "@/lib/utils/cn";
import type { Change24hCoverage } from "@/types/portfolio";
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
  const sign = classifyChangePercent(value);
  const Icon = sign === "positive" ? TrendingUp : TrendingDown;

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 font-semibold tabular-nums",
        sign === "positive"
          ? "text-profit"
          : sign === "negative"
            ? "text-loss"
            : "text-muted-foreground",
        size === "lg" ? "text-base" : "text-xs",
      )}
    >
      {sign === "neutral" ? (
        <Minus className={size === "lg" ? "h-4 w-4" : "h-3 w-3"} />
      ) : (
        <Icon className={size === "lg" ? "h-4 w-4" : "h-3 w-3"} />
      )}
      {showSign && sign === "positive" ? "+" : ""}
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

function isCount(n: unknown): n is number {
  return Number.isSafeInteger(n) && (n as number) >= 0;
}

/**
 * D11: only a complete, self-consistent coverage object may unlock the 24h totals. The backend
 * counts contributors within the counted holdings within all holdings, and partial is exactly
 * "some holding does not contribute"; anything else fails closed.
 */
function isChange24hCoverage(value: unknown): value is Change24hCoverage {
  if (value == null || typeof value !== "object") return false;
  const { holdingsWithChange, countedHoldings, totalHoldings, partial } = value as Record<string, unknown>;
  return (
    isCount(holdingsWithChange) &&
    isCount(countedHoldings) &&
    isCount(totalHoldings) &&
    holdingsWithChange <= countedHoldings &&
    countedHoldings <= totalHoldings &&
    partial === (holdingsWithChange < totalHoldings)
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export function SummaryCards() {
  const { data: portfolio, isLoading: isPortfolioLoading } = usePortfolio();
  const { data: portfolioSummary, isFetching: isSummaryFetching } =
    usePortfolioSummary();
  const { data: analytics } = usePortfolioAnalytics();

  // Skeleton only while we have neither summary nor the main portfolio payload yet.
  if (
    isSummaryFetching &&
    !portfolioSummary &&
    (isPortfolioLoading || !portfolio)
  ) {
    return <SummaryCardsSkeleton />;
  }

  // Prefer backend aggregate from /api/portfolio/summary (accurate SQL join) over
  // the frontend-computed value which depends on the market-data-service being up.
  const hasBackendTotal =
    portfolioSummary?.totalValue != null &&
    Number(portfolioSummary.totalValue) > 0;
  const portfolioTotal = hasBackendTotal
    ? Number(portfolioSummary.totalValue)
    : (portfolio?.summary.totalValue ?? 0);
  // The client-assembled total has no FX step, so it leaves out holdings not priced in USD
  // (rehearsal defect #4, fallback path); say so rather than present it as the whole portfolio.
  const fallbackTotalPartial = !hasBackendTotal && portfolio?.summary.partialValuation === true;

  // ── Task 9.4: bind analytics values for 24h P&L and all-time return ─────────
  // Use backend-computed values; fall back to null (renders "—").
  // Do NOT use the synthetic fetchPortfolio summary which is hardcoded to 0.
  const unrealizedPnLPercent = analytics?.totalUnrealizedPnLPercent ?? null;

  // D11 (finding F13): the backend's position-level 24h totals in base currency. Never a sum of
  // holdings' change24hAbsolute, which is a per-unit, quote-currency price change. The totals are
  // shown only with well-formed coverage: without it (an older backend, or a malformed object)
  // they fail closed to "—", and a partial total is labelled as such.
  const rawCoverage = analytics?.change24hCoverage;
  const change24hCoverage = isChange24hCoverage(rawCoverage) ? rawCoverage : null;
  const change24hAbsolute = change24hCoverage ? (analytics?.totalChange24hBase ?? null) : null;
  const change24hPercent = change24hCoverage ? (analytics?.totalChange24hPercent ?? null) : null;

  const isFlat24h = change24hAbsolute === 0;
  const pnlIsPositive = (change24hAbsolute ?? 0) > 0;
  const changeReferenceAt =
    analytics?.holdings?.find((h) => h.change24hReferenceAt)
      ?.change24hReferenceAt ?? null;

  // Use backend-computed performers; fall back to portfolio summary placeholder.
  const bestPerformer =
    analytics?.bestPerformer ?? portfolio?.summary.bestPerformer;
  const worstPerformer =
    analytics?.worstPerformer ?? portfolio?.summary.worstPerformer;

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
          {fallbackTotalPartial && (
            <span
              className="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400"
              data-testid="total-value-partial"
              title="Without portfolio analytics there is no exchange rate, so holdings priced in other currencies (or with no price) are left out"
            >
              <AlertCircle className="h-3 w-3 shrink-0" aria-hidden />
              Partial: USD-priced holdings only
            </span>
          )}
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            {unrealizedPnLPercent != null ? (
              <>
                <ChangeIndicator value={unrealizedPnLPercent} />
                <span>all-time return</span>
              </>
            ) : (
              <span className="text-muted-foreground">all-time return —</span>
            )}
          </div>
        </div>
        {/* Subtle gradient accent */}
        <div className="absolute -right-4 -top-4 h-20 w-20 rounded-full bg-profit/5 blur-xl" />
      </StatCard>

      {/* ── Card 2: 24h Profit / Loss ── */}
      {/* Task 9.4: bound to backend analytics, not the synthetic fetchPortfolio summary */}
      <StatCard
        title="24h Profit / Loss"
        icon={Activity}
        className={
          change24hAbsolute != null
            ? isFlat24h
              ? undefined
              : pnlIsPositive
                ? "border-profit/20"
                : "border-loss/20"
            : undefined
        }
      >
        <div className="space-y-1">
          {change24hAbsolute != null ? (
            <>
              <p
                className={cn(
                  "text-3xl font-bold tracking-tight tabular-nums",
                  isFlat24h
                    ? "text-muted-foreground"
                    : pnlIsPositive
                      ? "text-profit"
                      : "text-loss",
                )}
                data-testid="24h-pnl"
              >
                {formatSignedCurrencyOrDash(change24hAbsolute)}
              </p>
              <div className="flex items-center gap-2 flex-wrap">
                {change24hPercent != null && (
                  <ChangeIndicator value={change24hPercent} />
                )}
                <span className="text-xs text-muted-foreground">
                  {isFlat24h && changeReferenceAt
                    ? `unchanged since ${formatDate(changeReferenceAt)} snapshot`
                    : "since previous snapshot"}
                </span>
                {change24hCoverage?.partial && (
                  <span
                    className="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400"
                    data-testid="24h-coverage"
                    title={`24h change available for ${change24hCoverage.holdingsWithChange} of ${change24hCoverage.totalHoldings} holdings`}
                  >
                    <AlertCircle className="h-3 w-3 shrink-0" aria-hidden />
                    Partial: {change24hCoverage.holdingsWithChange} of {change24hCoverage.totalHoldings} holdings
                  </span>
                )}
              </div>
            </>
          ) : (
            <>
              <p
                className="text-3xl font-bold tracking-tight tabular-nums text-muted-foreground"
                data-testid="24h-pnl"
              >
                —
              </p>
              <span className="text-xs text-muted-foreground">
                {analytics && !change24hCoverage
                  ? "24h coverage unavailable"
                  : "no reference data available"}
              </span>
            </>
          )}
        </div>
        <div
          className={cn(
            "absolute -right-4 -top-4 h-20 w-20 rounded-full blur-xl",
            change24hAbsolute != null
              ? pnlIsPositive
                ? "bg-profit/8"
                : "bg-loss/8"
              : "bg-muted/20",
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
              {bestPerformer?.ticker ?? "—"}
            </Badge>
            {bestPerformer?.change24hPercent != null ? (
              <ChangeIndicator
                value={bestPerformer.change24hPercent}
                size="lg"
              />
            ) : (
              <span className="text-sm text-muted-foreground">—</span>
            )}
          </div>
          {bestPerformer && "name" in bestPerformer && (
            <p className="text-xs text-muted-foreground">
              {(bestPerformer as { name: string }).name}
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            Worst:{" "}
            <span className="font-mono font-semibold text-foreground">
              {worstPerformer?.ticker ?? "—"}
            </span>{" "}
            {worstPerformer?.change24hPercent != null ? (
              <span className="text-loss">
                {formatPercent(worstPerformer.change24hPercent)}
              </span>
            ) : (
              <span className="text-muted-foreground">—</span>
            )}
          </p>
        </div>
        <div className="absolute -right-4 -top-4 h-20 w-20 rounded-full bg-profit/5 blur-xl" />
      </StatCard>
    </>
  );
}
