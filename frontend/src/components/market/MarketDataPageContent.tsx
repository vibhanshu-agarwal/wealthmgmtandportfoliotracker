"use client";

import { useSession } from "@/lib/auth-client";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { usePortfolio } from "@/lib/hooks/usePortfolio";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils/cn";
import {
  formatCurrency,
  formatPercent,
  formatSignedCurrency,
  formatDate,
} from "@/lib/utils/format";

// ── Session-pending skeleton ──────────────────────────────────────────────────

function MarketDataPageSkeleton() {
  return (
    <Card>
      <CardHeader>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-64 mt-1" />
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full rounded-md" />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ── Data-loading skeleton (table rows) ────────────────────────────────────────

function MarketDataTableSkeleton() {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Market Prices</CardTitle>
        <CardDescription>
          Current prices and 24-hour changes for your holdings
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full rounded-md" />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ── Session gate ──────────────────────────────────────────────────────────────

/**
 * Client component that gates market data behind a confirmed session.
 *
 * - session pending → page skeleton
 * - unauthenticated → redirect to /login, render nothing
 * - authenticated + loading → table skeleton
 * - authenticated + empty → "No market data" fallback
 * - authenticated + error → error fallback
 * - authenticated + data → price ticker table
 */
export function MarketDataPageContent() {
  const { data: session, isPending } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (!isPending && !session?.user) {
      router.replace("/login");
    }
  }, [isPending, session, router]);

  if (isPending) {
    return <MarketDataPageSkeleton />;
  }

  if (!session?.user) {
    return null;
  }

  return <MarketDataTable />;
}

// ── Authenticated market data table ───────────────────────────────────────────

function MarketDataTable() {
  const { data, isLoading, isError } = usePortfolio();

  if (isLoading) {
    return <MarketDataTableSkeleton />;
  }

  if (isError) {
    return (
      <Card>
        <CardContent className="py-10 text-center">
          <p className="text-muted-foreground">
            Unable to load market data. Please try again later.
          </p>
        </CardContent>
      </Card>
    );
  }

  const holdings = data?.holdings ?? [];

  if (holdings.length === 0) {
    return (
      <Card>
        <CardContent className="py-10 text-center">
          <p className="text-muted-foreground">No market data available.</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Market Prices</CardTitle>
        <CardDescription>
          Current prices and 24-hour changes for your holdings
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Ticker</TableHead>
              <TableHead className="text-right">Current Price</TableHead>
              <TableHead className="text-right">24h Change</TableHead>
              <TableHead className="text-right">Last Updated</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {holdings.map((holding) => (
              <TableRow key={holding.id}>
                <TableCell>
                  <Badge variant="secondary" className="font-mono">
                    {holding.ticker}
                  </Badge>
                </TableCell>
                <TableCell className="text-right">
                  {formatCurrency(holding.currentPrice)}
                </TableCell>
                <TableCell
                  className={cn(
                    "text-right",
                    holding.change24hPercent >= 0
                      ? "text-green-600"
                      : "text-red-600",
                  )}
                >
                  {formatPercent(holding.change24hPercent)}{" "}
                  <span className="text-xs">
                    ({formatSignedCurrency(holding.change24hAbsolute)})
                  </span>
                </TableCell>
                <TableCell className="text-right text-muted-foreground">
                  {formatDate(holding.lastUpdatedAt)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
