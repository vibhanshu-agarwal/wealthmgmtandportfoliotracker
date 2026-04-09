"use client";

import { useState, useMemo } from "react";
import {
  TrendingUp,
  TrendingDown,
  Minus,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  Search,
} from "lucide-react";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { usePortfolio, usePortfolioAnalytics } from "@/lib/hooks/usePortfolio";
import {
  formatCurrency,
  formatPercent,
  formatSignedCurrency,
  formatQuantity,
} from "@/lib/utils/format";
import { cn } from "@/lib/utils/cn";
import type { AssetClass, AssetHoldingDTO } from "@/types/portfolio";

// ── Types ─────────────────────────────────────────────────────────────────────

type SortKey =
  | "ticker"
  | "quantity"
  | "currentPrice"
  | "totalValue"
  | "change24hPercent"
  | "unrealizedPnL";
type SortDir = "asc" | "desc";

// ── Asset class config ────────────────────────────────────────────────────────

const ASSET_CLASS_CONFIG: Record<
  AssetClass,
  { label: string; color: string; bgClass: string; textClass: string }
> = {
  STOCK: {
    label: "Stock",
    color: "hsl(160 84% 39%)",
    bgClass: "bg-emerald-500/10",
    textClass: "text-emerald-600 dark:text-emerald-400",
  },
  ETF: {
    label: "ETF",
    color: "hsl(217 91% 60%)",
    bgClass: "bg-blue-500/10",
    textClass: "text-blue-600 dark:text-blue-400",
  },
  CRYPTO: {
    label: "Crypto",
    color: "hsl(37 91% 55%)",
    bgClass: "bg-amber-500/10",
    textClass: "text-amber-600 dark:text-amber-400",
  },
  BOND: {
    label: "Bond",
    color: "hsl(270 95% 75%)",
    bgClass: "bg-violet-500/10",
    textClass: "text-violet-600 dark:text-violet-400",
  },
  CASH: {
    label: "Cash",
    color: "hsl(215 16% 47%)",
    bgClass: "bg-slate-500/10",
    textClass: "text-slate-500",
  },
  COMMODITY: {
    label: "Commodity",
    color: "hsl(0 72% 51%)",
    bgClass: "bg-red-500/10",
    textClass: "text-red-600 dark:text-red-400",
  },
};

const ALL_ASSET_CLASSES = Object.keys(ASSET_CLASS_CONFIG) as AssetClass[];

// ── Sub-components ────────────────────────────────────────────────────────────

function ChangeCell({
  percent,
  absolute,
}: {
  percent: number;
  absolute: number;
}) {
  const isPositive = percent > 0;
  const isNeutral = percent === 0;
  const Icon = isNeutral ? Minus : isPositive ? TrendingUp : TrendingDown;

  return (
    <div className="flex flex-col items-end gap-0.5">
      <span
        className={cn(
          "inline-flex items-center gap-1 text-sm font-semibold tabular-nums",
          isNeutral && "text-muted-foreground",
          isPositive && "text-profit",
          !isPositive && !isNeutral && "text-loss",
        )}
      >
        <Icon className="h-3.5 w-3.5 shrink-0" />
        {isPositive ? "+" : ""}
        {percent.toFixed(2)}%
      </span>
      <span
        className={cn(
          "text-xs tabular-nums",
          isNeutral && "text-muted-foreground",
          isPositive && "text-profit/70",
          !isPositive && !isNeutral && "text-loss/70",
        )}
      >
        {formatSignedCurrency(absolute)}
      </span>
    </div>
  );
}

function SortIcon({
  column,
  sortKey,
  sortDir,
}: {
  column: SortKey;
  sortKey: SortKey;
  sortDir: SortDir;
}) {
  if (column !== sortKey)
    return <ArrowUpDown className="ml-1.5 h-3 w-3 text-muted-foreground/50" />;
  return sortDir === "asc" ? (
    <ArrowUp className="ml-1.5 h-3 w-3 text-foreground" />
  ) : (
    <ArrowDown className="ml-1.5 h-3 w-3 text-foreground" />
  );
}

// ── Column header button ──────────────────────────────────────────────────────

function ColHeader({
  label,
  column,
  sortKey,
  sortDir,
  onSort,
  align = "left",
}: {
  label: string;
  column: SortKey;
  sortKey: SortKey;
  sortDir: SortDir;
  onSort: (col: SortKey) => void;
  align?: "left" | "right";
}) {
  return (
    <TableHead className={cn(align === "right" && "text-right")}>
      <button
        onClick={() => onSort(column)}
        className={cn(
          "inline-flex items-center gap-0 text-xs font-semibold uppercase tracking-wide text-muted-foreground hover:text-foreground transition-colors",
          align === "right" && "flex-row-reverse",
        )}
      >
        {label}
        <SortIcon column={column} sortKey={sortKey} sortDir={sortDir} />
      </button>
    </TableHead>
  );
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function HoldingsTableSkeleton() {
  return (
    <Card>
      <CardHeader>
        <Skeleton className="h-5 w-32" />
        <Skeleton className="h-4 w-48 mt-1" />
      </CardHeader>
      <CardContent>
        <Skeleton className="h-9 w-64 mb-4" />
        <div className="space-y-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full rounded-md" />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export function HoldingsTable() {
  const { data: portfolio, isLoading } = usePortfolio();
  const { data: analytics } = usePortfolioAnalytics();

  // Build a ticker → analytics holding lookup for merging real P&L and 24h change data.
  const analyticsByTicker = new Map(
    (analytics?.holdings ?? []).map((h) => [h.ticker, h]),
  );

  const [sortKey, setSortKey] = useState<SortKey>("totalValue");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [search, setSearch] = useState("");
  const [activeClass, setActiveClass] = useState<AssetClass | "ALL">("ALL");

  function handleSort(col: SortKey) {
    if (col === sortKey) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(col);
      setSortDir("desc");
    }
  }

  const rows = useMemo<AssetHoldingDTO[]>(() => {
    if (!portfolio) return [];

    let filtered = portfolio.holdings.map((h) => {
      // Merge backend-computed analytics fields by ticker when available.
      const analyticsHolding = analyticsByTicker.get(h.ticker);
      if (!analyticsHolding) return h;
      return {
        ...h,
        unrealizedPnL: analyticsHolding.unrealizedPnL,
        change24hPercent: analyticsHolding.change24hPercent,
        change24hAbsolute: analyticsHolding.change24hAbsolute,
      };
    });

    // Asset class filter
    if (activeClass !== "ALL") {
      filtered = filtered.filter((h) => h.assetClass === activeClass);
    }

    // Search filter
    if (search.trim()) {
      const q = search.toLowerCase();
      filtered = filtered.filter(
        (h) =>
          h.ticker.toLowerCase().includes(q) ||
          h.name.toLowerCase().includes(q),
      );
    }

    // Sort
    return [...filtered].sort((a, b) => {
      const aVal = a[sortKey as keyof AssetHoldingDTO] as number | string;
      const bVal = b[sortKey as keyof AssetHoldingDTO] as number | string;
      const cmp = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [portfolio, sortKey, sortDir, search, activeClass, analyticsByTicker]);

  if (isLoading) return <HoldingsTableSkeleton />;

  // Show empty table when backend is unreachable rather than a hard error
  if (!portfolio) {
    return (
      <Card>
        <CardHeader className="pb-4">
          <CardTitle className="text-base">Holdings</CardTitle>
          <CardDescription className="mt-1">
            No holdings data available
          </CardDescription>
        </CardHeader>
        <CardContent className="flex items-center justify-center p-8 text-sm text-muted-foreground">
          Connect to the backend to view your holdings.
        </CardContent>
      </Card>
    );
  }

  // Total row values
  const totals = rows.reduce(
    (acc, h) => ({
      value: acc.value + h.totalValue,
      pnl: acc.pnl + h.unrealizedPnL,
      abs24h: acc.abs24h + h.change24hAbsolute,
    }),
    { value: 0, pnl: 0, abs24h: 0 },
  );

  return (
    <Card>
      <CardHeader className="pb-4">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div>
            <CardTitle className="text-base">Holdings</CardTitle>
            <CardDescription className="mt-1">
              {rows.length} of {portfolio.holdings.length} asset
              {portfolio.holdings.length !== 1 ? "s" : ""}
            </CardDescription>
          </div>

          {/* Search */}
          <div className="relative w-56">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
            <Input
              placeholder="Search ticker or name…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9 h-8 text-sm"
            />
          </div>
        </div>

        {/* Asset class filter chips */}
        <div className="flex flex-wrap gap-1.5 mt-3">
          <button
            onClick={() => setActiveClass("ALL")}
            className={cn(
              "rounded-full px-3 py-0.5 text-xs font-semibold transition-colors",
              activeClass === "ALL"
                ? "bg-foreground text-background"
                : "bg-muted text-muted-foreground hover:text-foreground",
            )}
          >
            All
          </button>
          {ALL_ASSET_CLASSES.filter((cls) =>
            portfolio.holdings.some((h) => h.assetClass === cls),
          ).map((cls) => {
            const cfg = ASSET_CLASS_CONFIG[cls];
            return (
              <button
                key={cls}
                onClick={() =>
                  setActiveClass(activeClass === cls ? "ALL" : cls)
                }
                className={cn(
                  "rounded-full px-3 py-0.5 text-xs font-semibold transition-colors",
                  activeClass === cls
                    ? `${cfg.bgClass} ${cfg.textClass}`
                    : "bg-muted text-muted-foreground hover:text-foreground",
                )}
              >
                {cfg.label}
              </button>
            );
          })}
        </div>
      </CardHeader>

      <CardContent className="pt-0 px-0 pb-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="border-b border-border hover:bg-transparent">
                <ColHeader
                  label="Asset"
                  column="ticker"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                />
                <ColHeader
                  label="Quantity"
                  column="quantity"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                  align="right"
                />
                <ColHeader
                  label="Price"
                  column="currentPrice"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                  align="right"
                />
                <ColHeader
                  label="Value"
                  column="totalValue"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                  align="right"
                />
                <ColHeader
                  label="Unr. P&L"
                  column="unrealizedPnL"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                  align="right"
                />
                <ColHeader
                  label="24h Change"
                  column="change24hPercent"
                  sortKey={sortKey}
                  sortDir={sortDir}
                  onSort={handleSort}
                  align="right"
                />
              </TableRow>
            </TableHeader>

            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell
                    colSpan={6}
                    className="h-24 text-center text-sm text-muted-foreground"
                  >
                    No holdings match your filter.
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((holding) => {
                  const cfg = ASSET_CLASS_CONFIG[holding.assetClass];
                  const pnlPositive = holding.unrealizedPnL >= 0;

                  return (
                    <TableRow
                      key={holding.id}
                      className="group transition-colors hover:bg-muted/40"
                    >
                      {/* ── Asset ── */}
                      <TableCell className="py-3 pl-6">
                        <div className="flex items-center gap-3">
                          {/* Color swatch */}
                          <div
                            className="h-8 w-1 rounded-full shrink-0"
                            style={{ backgroundColor: cfg.color }}
                          />
                          <div>
                            <div className="flex items-center gap-2">
                              <span className="font-mono font-bold text-sm tracking-wide">
                                {holding.ticker}
                              </span>
                              <span
                                className={cn(
                                  "rounded-full px-2 py-0.5 text-[10px] font-semibold",
                                  cfg.bgClass,
                                  cfg.textClass,
                                )}
                              >
                                {cfg.label}
                              </span>
                            </div>
                            <p className="text-xs text-muted-foreground mt-0.5 leading-none">
                              {holding.name}
                            </p>
                          </div>
                        </div>
                      </TableCell>

                      {/* ── Quantity ── */}
                      <TableCell className="text-right tabular-nums text-sm">
                        {formatQuantity(holding.quantity)}
                      </TableCell>

                      {/* ── Price ── */}
                      <TableCell className="text-right tabular-nums text-sm font-medium">
                        {formatCurrency(holding.currentPrice)}
                      </TableCell>

                      {/* ── Value ── */}
                      <TableCell className="text-right">
                        <div className="flex flex-col items-end gap-0.5">
                          <span className="tabular-nums text-sm font-semibold">
                            {formatCurrency(holding.totalValue)}
                          </span>
                          <Badge
                            variant="secondary"
                            className="text-[10px] h-4 px-1.5 font-normal"
                          >
                            {holding.portfolioWeight.toFixed(1)}%
                          </Badge>
                        </div>
                      </TableCell>

                      {/* ── Unrealized P&L ── */}
                      <TableCell className="text-right">
                        <div className="flex flex-col items-end gap-0.5">
                          <span
                            className={cn(
                              "tabular-nums text-sm font-semibold",
                              pnlPositive ? "text-profit" : "text-loss",
                            )}
                          >
                            {formatSignedCurrency(holding.unrealizedPnL)}
                          </span>
                          <span
                            className={cn(
                              "text-xs tabular-nums",
                              pnlPositive ? "text-profit/70" : "text-loss/70",
                            )}
                          >
                            {formatPercent(
                              ((holding.totalValue -
                                holding.avgCostBasis * holding.quantity) /
                                (holding.avgCostBasis * holding.quantity)) *
                                100,
                            )}
                          </span>
                        </div>
                      </TableCell>

                      {/* ── 24h Change ── */}
                      <TableCell className="text-right pr-6">
                        <ChangeCell
                          percent={holding.change24hPercent}
                          absolute={holding.change24hAbsolute}
                        />
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>

        {/* ── Totals footer ── */}
        {rows.length > 0 && (
          <div className="flex items-center justify-between border-t border-border px-6 py-3 text-sm">
            <span className="font-semibold text-muted-foreground">
              Total ({rows.length} assets)
            </span>
            <div className="flex items-center gap-8">
              <div className="text-right">
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground">
                  Value
                </p>
                <p className="font-bold tabular-nums">
                  {formatCurrency(totals.value)}
                </p>
              </div>
              <div className="text-right">
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground">
                  Unr. P&L
                </p>
                <p
                  className={cn(
                    "font-bold tabular-nums",
                    totals.pnl >= 0 ? "text-profit" : "text-loss",
                  )}
                >
                  {formatSignedCurrency(totals.pnl)}
                </p>
              </div>
              <div className="text-right">
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground">
                  24h
                </p>
                <p
                  className={cn(
                    "font-bold tabular-nums",
                    totals.abs24h >= 0 ? "text-profit" : "text-loss",
                  )}
                >
                  {formatSignedCurrency(totals.abs24h)}
                </p>
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
