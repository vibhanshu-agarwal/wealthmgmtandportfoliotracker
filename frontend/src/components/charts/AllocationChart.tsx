"use client";

import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAssetAllocation } from "@/lib/hooks/usePortfolio";
import { formatCurrency, formatPercent } from "@/lib/utils/format";
import type { AllocationSliceDTO } from "@/types/portfolio";

// ── Custom tooltip ────────────────────────────────────────────────────────────

function AllocationTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ payload: AllocationSliceDTO }>;
}) {
  if (!active || !payload?.length) return null;
  const slice = payload[0].payload;

  return (
    <div className="rounded-lg border border-border bg-card px-3 py-2.5 shadow-lg text-sm">
      <p className="font-semibold text-foreground">{slice.label}</p>
      <p className="text-xs text-muted-foreground mt-0.5">
        {formatCurrency(slice.value)}
      </p>
      <p
        className="text-xs font-semibold mt-0.5"
        style={{ color: slice.color }}
      >
        {formatPercent(slice.percentage)}
      </p>
    </div>
  );
}

// ── Legend ────────────────────────────────────────────────────────────────────

function AllocationLegend({ slices }: { slices: AllocationSliceDTO[] }) {
  return (
    <ul className="space-y-2 mt-4">
      {slices
        .sort((a, b) => b.percentage - a.percentage)
        .map((slice) => (
          <li
            key={slice.assetClass}
            className="flex items-center justify-between text-sm"
          >
            <div className="flex items-center gap-2">
              <span
                className="h-2.5 w-2.5 rounded-full shrink-0"
                style={{ backgroundColor: slice.color }}
              />
              <span className="text-muted-foreground">{slice.label}</span>
            </div>
            <div className="flex items-center gap-2 tabular-nums">
              <span className="font-semibold text-foreground">
                {slice.percentage.toFixed(1)}%
              </span>
            </div>
          </li>
        ))}
    </ul>
  );
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function AllocationChartSkeleton() {
  return (
    <Card>
      <CardHeader>
        <Skeleton className="h-5 w-36" />
        <Skeleton className="h-4 w-28 mt-1" />
      </CardHeader>
      <CardContent className="flex flex-col items-center gap-4">
        <Skeleton className="h-44 w-44 rounded-full" />
        <div className="w-full space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-4 w-full" />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export function AllocationChart() {
  const { data, isLoading } = useAssetAllocation();

  if (isLoading) return <AllocationChartSkeleton />;

  if (!data || data.slices.length === 0) {
    return (
      <Card className="flex items-center justify-center p-8 text-muted-foreground text-sm">
        No allocation data available.
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">Asset Allocation</CardTitle>
        <CardDescription>
          {formatCurrency(data.totalValue)} total
        </CardDescription>
      </CardHeader>

      <CardContent className="pt-0">
        {/* Donut chart */}
        <ResponsiveContainer width="100%" height={180}>
          <PieChart>
            <Pie
              data={data.slices}
              cx="50%"
              cy="50%"
              innerRadius={52}
              outerRadius={80}
              paddingAngle={2}
              dataKey="value"
              nameKey="label"
              strokeWidth={0}
            >
              {data.slices.map((slice) => (
                <Cell key={slice.assetClass} fill={slice.color} opacity={0.9} />
              ))}
            </Pie>
            <Tooltip content={<AllocationTooltip />} />
          </PieChart>
        </ResponsiveContainer>

        {/* Legend */}
        <AllocationLegend slices={data.slices} />
      </CardContent>
    </Card>
  );
}
