"use client";

import { useAuthSession } from "@/lib/auth/session";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import Link from "next/link";
import { SummaryCards } from "@/components/portfolio/SummaryCards";
import { PerformanceChart } from "@/components/charts/PerformanceChart";
import { AllocationChart } from "@/components/charts/AllocationChart";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

// ── Loading skeleton that mirrors the overview page layout ────────────────────

function OverviewPageSkeleton() {
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

      {/* Link placeholder */}
      <Card>
        <CardContent className="py-4">
          <Skeleton className="h-5 w-36" />
        </CardContent>
      </Card>
    </div>
  );
}

// ── Session gate ──────────────────────────────────────────────────────────────

/**
 * Client component that gates overview dashboard components behind a
 * confirmed session.
 *
 * - loading       → render skeleton (session not yet resolved)
 * - authenticated → render summary + charts + portfolio link
 * - unauthenticated → redirect to /login, render nothing
 */
export function OverviewPageContent() {
  const { data: session, isPending } = useAuthSession();
  const router = useRouter();

  useEffect(() => {
    if (!isPending && !session) {
      router.replace("/login");
    }
  }, [isPending, session, router]);

  if (isPending) {
    return <OverviewPageSkeleton />;
  }

  if (!session) {
    return null;
  }

  return (
    <>
      {/* ── Row 1: Summary cards ── */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SummaryCards />
      </div>

      {/* ── Row 2: Performance chart + Allocation donut ── */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <PerformanceChart />
        <AllocationChart />
      </div>

      {/* ── Row 3: Navigate to full portfolio ── */}
      <Card>
        <CardContent className="py-4">
          <Link
            href="/portfolio"
            className="inline-flex items-center text-sm font-medium text-primary hover:underline"
          >
            View Portfolio →
          </Link>
        </CardContent>
      </Card>
    </>
  );
}
