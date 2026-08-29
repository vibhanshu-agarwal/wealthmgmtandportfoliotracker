/**
 * B2 Task 1.16 — pure text derivation for the compact freshness status strip.
 */
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";

export type FreshnessSeverity = "fresh" | "attention";

export interface FreshnessDescription {
  severity: FreshnessSeverity;
  /** e.g. "1 holding stale, 145 fresh" or "All prices fresh". */
  summary: string;
  /** "3 days ago" style, or the absent-timestamp copy — requirements.md 3a. */
  timestampLabel: string;
}

const STATE_LABEL: Record<AssetPriceFreshnessDTO["state"], string> = {
  FRESH: "fresh",
  STALE: "stale",
  UNKNOWN: "unknown",
  MISSING: "missing",
};

function relativeTimeFromNow(iso: string): string {
  const deltaMs = Date.now() - new Date(iso).getTime();
  const days = Math.floor(deltaMs / 86_400_000);
  if (days >= 1) return `${days} day${days === 1 ? "" : "s"} ago`;
  const hours = Math.floor(deltaMs / 3_600_000);
  if (hours >= 1) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const minutes = Math.max(0, Math.floor(deltaMs / 60_000));
  return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
}

/**
 * The affected-holding count for the portfolio-level `state`, per the most-severe
 * reduction MISSING > UNKNOWN > STALE > FRESH (Spec A design.md §7).
 */
function affectedCount(freshness: AssetPriceFreshnessDTO): number {
  switch (freshness.state) {
    case "MISSING":
      return freshness.missingPriceHoldings;
    case "UNKNOWN":
      return freshness.unknownPriceHoldings;
    case "STALE":
      return freshness.staleHoldings;
    case "FRESH":
      return 0;
  }
}

export function describeFreshness(freshness: AssetPriceFreshnessDTO): FreshnessDescription {
  const severity: FreshnessSeverity = freshness.state === "FRESH" ? "fresh" : "attention";
  const count = affectedCount(freshness);
  const summary =
    severity === "fresh"
      ? "All prices fresh"
      : `${count} holding${count === 1 ? "" : "s"} ${STATE_LABEL[freshness.state]}`;

  const timestampLabel = freshness.oldestKnownAssetPriceObservationTimestamp
    ? relativeTimeFromNow(freshness.oldestKnownAssetPriceObservationTimestamp)
    : "No price observation on record";

  return { severity, summary, timestampLabel };
}

export interface FreshnessRow {
  label: string;
  /** `null` for the single "All prices fresh" line — there is no count to show. */
  count: number | null;
}

/**
 * requirements.md 3a — one line per non-`FRESH` state present, omitting a state
 * entirely when its count is zero (never a "0" row); a single "all fresh" line when
 * every count is zero.
 */
export function buildFreshnessRows(freshness: AssetPriceFreshnessDTO): FreshnessRow[] {
  const rows: FreshnessRow[] = [];
  if (freshness.missingPriceHoldings > 0) {
    rows.push({ label: "Missing", count: freshness.missingPriceHoldings });
  }
  if (freshness.unknownPriceHoldings > 0) {
    rows.push({ label: "Unknown", count: freshness.unknownPriceHoldings });
  }
  if (freshness.staleHoldings > 0) {
    rows.push({ label: "Stale", count: freshness.staleHoldings });
  }
  return rows.length > 0 ? rows : [{ label: "All prices fresh", count: null }];
}

const ABSOLUTE_TIMESTAMP_FORMAT = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
});

/**
 * requirements.md 3a — the popover restates the timestamp as an absolute date-time,
 * not the inline strip's relative phrasing ("3 days ago" is a summary, not this
 * disclosure). The absent-timestamp case says so explicitly rather than a blank.
 */
export function formatAbsoluteTimestamp(iso: string | undefined): string {
  if (!iso) return "No price observation on record";
  return ABSOLUTE_TIMESTAMP_FORMAT.format(new Date(iso));
}
