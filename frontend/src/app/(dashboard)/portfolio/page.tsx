import type { Metadata } from "next";
import { SummaryCards } from "@/components/portfolio/SummaryCards";
import { PerformanceChart } from "@/components/charts/PerformanceChart";
import { AllocationChart } from "@/components/charts/AllocationChart";
import { HoldingsTable } from "@/components/portfolio/HoldingsTable";

export const metadata: Metadata = {
  title: "Portfolio",
};

/**
 * Portfolio dashboard page — Server Component.
 * Child Client Components independently fetch their own data via React Query.
 */
export default function PortfolioPage() {
  return (
    <div className="space-y-6">
      {/* ── Page header ── */}
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Portfolio</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Real-time overview of your holdings and performance.
        </p>
      </div>

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
    </div>
  );
}
