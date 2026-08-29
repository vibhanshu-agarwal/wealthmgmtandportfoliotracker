import { cn } from "@/lib/utils/cn";
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";
import { describeFreshness } from "./freshnessFormat";
import { FreshnessDetailsPopover } from "./FreshnessDetailsPopover";

export interface FreshnessStatusProps {
  freshness: AssetPriceFreshnessDTO | undefined;
}

/**
 * B2 Task 1.16 — one compact freshness status at the portfolio level (requirements.md
 * 3.2). Renders nothing while the summary hasn't loaded yet, rather than a placeholder
 * that implies a known-fresh state.
 */
export function FreshnessStatus({ freshness }: FreshnessStatusProps) {
  if (!freshness) return null;
  const { severity, summary, timestampLabel } = describeFreshness(freshness);

  return (
    <div
      className={cn(
        "mb-5 flex items-center gap-2 rounded-lg border px-3.5 py-2.5 text-sm",
        severity === "attention"
          ? "border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200"
          : "border-border bg-muted/40 text-muted-foreground",
      )}
    >
      <span>
        Prices as of <strong>{timestampLabel}</strong> — {summary}
      </span>
      <span className="ml-auto">
        <FreshnessDetailsPopover freshness={freshness} />
      </span>
    </div>
  );
}
