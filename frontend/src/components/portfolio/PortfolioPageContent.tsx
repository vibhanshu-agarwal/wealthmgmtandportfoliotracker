"use client";

import { useAuthSession } from "@/lib/auth/session";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { SummaryCards } from "@/components/portfolio/SummaryCards";
import { PerformanceChart } from "@/components/charts/PerformanceChart";
import { AllocationChart } from "@/components/charts/AllocationChart";
import { HoldingsTable } from "@/components/portfolio/HoldingsTable";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { EditHoldingsButton } from "@/components/asset-picker/EditHoldingsButton";
import { ManualResetControl } from "@/components/asset-picker/ManualResetControl";
import { FreshnessStatus } from "@/components/freshness/FreshnessStatus";
import { isAssetPickerEnabled, isDemoResetControlEnabled } from "@/lib/config/assetPickerFeatures";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";
import { usePortfolio, usePortfolioSummary } from "@/lib/hooks/usePortfolio";

// ── Loading skeleton that mirrors the portfolio page layout ───────────────────

function PortfolioPageSkeleton() {
  return (
    <div className="space-y-6">
      {/* Summary cards row */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
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
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Card>
            <CardHeader>
              <Skeleton className="h-5 w-40" />
            </CardHeader>
            <CardContent>
              <Skeleton className="h-48 w-full" />
            </CardContent>
          </Card>
        </div>
        <Card>
          <CardHeader>
            <Skeleton className="h-5 w-32" />
          </CardHeader>
          <CardContent>
            <Skeleton className="h-48 w-full rounded-full" />
          </CardContent>
        </Card>
      </div>

      {/* Holdings table */}
      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-4 w-48 mt-1" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-9 w-64 mb-4" />
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-12 w-full rounded-md" />
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

// ── Session gate ──────────────────────────────────────────────────────────────

/**
 * Client component that strictly gates portfolio data components behind a
 * confirmed NextAuth session.
 *
 * - loading       → render skeleton (session not yet resolved)
 * - authenticated → render data components (token guaranteed present)
 * - unauthenticated → redirect to /login, render nothing
 */
export function PortfolioPageContent() {
  const { data: session, isPending } = useAuthSession();
  const router = useRouter();
  const { userId, token } = useAuthenticatedUserId();
  const { data: portfolio } = usePortfolio();
  const { data: summary } = usePortfolioSummary();

  useEffect(() => {
    if (!isPending && !session) {
      router.replace("/login");
    }
  }, [isPending, session, router]);

  if (isPending) {
    return <PortfolioPageSkeleton />;
  }

  if (!session) {
    return null;
  }

  return (
    <>
      {/* Task 1.16/1.18: sourced from usePortfolioSummary, re-read (not inferred)
          after a successful save via that query's own invalidation. */}
      <FreshnessStatus freshness={summary?.assetPriceFreshness} />

      {/* B2 Tasks 6.1/6.2: temporary page-level host for the hidden manual-reset
          control, independently flagged from the picker (requirements.md 7.6's
          final placement is still OPEN — see ManualResetControl's own doc comment).
          Proving these two flags independently is an explicit acceptance point:
          neither block below depends on the other's condition. */}
      {isDemoResetControlEnabled() && portfolio && (
        <div className="flex justify-end">
          <ManualResetControl
            userId={userId}
            token={token}
            version={portfolio.version}
            versionObserved={portfolio.versionObserved !== false}
          />
        </div>
      )}

      {isAssetPickerEnabled() && portfolio && (
        <div className="flex justify-end">
          <EditHoldingsButton
            holdings={portfolio.holdings}
            version={portfolio.version}
            userId={userId}
            token={token}
          />
        </div>
      )}

      {/* ── Row 1: Summary cards ── */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SummaryCards />
      </div>

      {/* ── Row 2: Performance chart + Allocation donut ── */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <PerformanceChart />
        <AllocationChart />
      </div>

      {/* ── Row 3: Holdings data table ── */}
      <HoldingsTable />
    </>
  );
}
