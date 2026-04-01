"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { TrendingUp, TrendingDown } from "lucide-react";
import { usePortfolioPerformance } from "@/lib/hooks/usePortfolio";
import { formatCurrency, formatDateShort, formatSignedCurrency, formatPercent } from "@/lib/utils/format";
import { cn } from "@/lib/utils/cn";
import type { PerformanceDataPoint } from "@/types/portfolio";

// ── Custom tooltip ────────────────────────────────────────────────────────────

function ChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ value: number; payload: PerformanceDataPoint }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;

  const point = payload[0].payload;
  const isPositive = point.change >= 0;

  return (
    <div className="rounded-lg border border-border bg-card px-3 py-2.5 shadow-lg text-sm">
      <p className="text-xs text-muted-foreground mb-1">
        {label ? formatDateShort(label) : ""}
      </p>
      <p className="font-semibold tabular-nums text-foreground">
        {formatCurrency(point.value)}
      </p>
      <p className={cn("text-xs tabular-nums mt-0.5", isPositive ? "text-profit" : "text-loss")}>
        {isPositive ? "+" : ""}
        {formatCurrency(point.change)} today
      </p>
    </div>
  );
}

// ── Period selector ───────────────────────────────────────────────────────────

const PERIODS = [
  { label: "7D",  days: 7  },
  { label: "30D", days: 30 },
  { label: "90D", days: 90 },
] as const;

// ── Skeleton ──────────────────────────────────────────────────────────────────

function PerformanceChartSkeleton() {
  return (
    <Card className="col-span-2">
      <CardHeader>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-56 mt-1" />
      </CardHeader>
      <CardContent>
        <Skeleton className="h-56 w-full rounded-lg" />
      </CardContent>
    </Card>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

interface PerformanceChartProps {
  days?: number;
}

export function PerformanceChart({ days = 30 }: PerformanceChartProps) {
  const { data, isLoading, isError } = usePortfolioPerformance("user-001", days);

  if (isLoading) return <PerformanceChartSkeleton />;

  if (isError || !data) {
    return (
      <Card className="col-span-2 flex items-center justify-center p-8 text-muted-foreground">
        Failed to load performance data.
      </Card>
    );
  }

  const isPositivePeriod = data.periodReturn >= 0;

  return (
    <Card className="col-span-2">
      <CardHeader className="pb-4">
        <div className="flex items-start justify-between">
          <div>
            <CardTitle className="text-base">Portfolio Performance</CardTitle>
            <CardDescription className="mt-1 flex items-center gap-2">
              <span>{days}-day return:</span>
              <span
                className={cn(
                  "inline-flex items-center gap-1 font-semibold tabular-nums",
                  isPositivePeriod ? "text-profit" : "text-loss"
                )}
              >
                {isPositivePeriod ? (
                  <TrendingUp className="h-3 w-3" />
                ) : (
                  <TrendingDown className="h-3 w-3" />
                )}
                {formatSignedCurrency(data.periodReturn)} ({formatPercent(data.periodReturnPercent)})
              </span>
            </CardDescription>
          </div>

          {/* Period badges — wired up in a parent if state is needed */}
          <div className="flex gap-1">
            {PERIODS.map((p) => (
              <Badge
                key={p.label}
                variant={p.days === days ? "default" : "secondary"}
                className="cursor-pointer text-xs"
              >
                {p.label}
              </Badge>
            ))}
          </div>
        </div>
      </CardHeader>

      <CardContent className="pt-0">
        <ResponsiveContainer width="100%" height={224}>
          <AreaChart
            data={data.dataPoints}
            margin={{ top: 4, right: 4, left: 0, bottom: 0 }}
          >
            <defs>
              <linearGradient id="portfolioGradient" x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="5%"
                  stopColor={isPositivePeriod ? "hsl(160 84% 39%)" : "hsl(0 72% 51%)"}
                  stopOpacity={0.25}
                />
                <stop
                  offset="95%"
                  stopColor={isPositivePeriod ? "hsl(160 84% 39%)" : "hsl(0 72% 51%)"}
                  stopOpacity={0}
                />
              </linearGradient>
            </defs>

            <CartesianGrid
              strokeDasharray="3 3"
              stroke="hsl(215 20% 89%)"
              className="dark:[&>line]:stroke-[hsl(215_25%_18%)]"
              vertical={false}
            />

            <XAxis
              dataKey="date"
              tickFormatter={formatDateShort}
              tick={{ fontSize: 11, fill: "hsl(215 16% 47%)" }}
              tickLine={false}
              axisLine={false}
              interval={Math.floor(data.dataPoints.length / 6)}
            />

            <YAxis
              tickFormatter={(v) => `$${(v / 1000).toFixed(0)}K`}
              tick={{ fontSize: 11, fill: "hsl(215 16% 47%)" }}
              tickLine={false}
              axisLine={false}
              width={52}
              domain={["auto", "auto"]}
            />

            <Tooltip
              content={<ChartTooltip />}
              cursor={{
                stroke: isPositivePeriod ? "hsl(160 84% 39%)" : "hsl(0 72% 51%)",
                strokeWidth: 1,
                strokeDasharray: "4 2",
              }}
            />

            <Area
              type="monotone"
              dataKey="value"
              stroke={isPositivePeriod ? "hsl(160 84% 39%)" : "hsl(0 72% 51%)"}
              strokeWidth={2}
              fill="url(#portfolioGradient)"
              dot={false}
              activeDot={{
                r: 4,
                fill: isPositivePeriod ? "hsl(160 84% 39%)" : "hsl(0 72% 51%)",
                strokeWidth: 2,
                stroke: "hsl(0 0% 100%)",
              }}
            />
          </AreaChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}
