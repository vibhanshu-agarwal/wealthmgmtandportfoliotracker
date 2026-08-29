/**
 * B2 Task 1.16 — pure text derivation for the compact freshness status strip, kept
 * separate from rendering so the "N holdings stale" / "Prices as of ___" phrasing is
 * unit-testable without mounting a component.
 */
import { describe, expect, it } from "vitest";
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";
import { buildFreshnessRows, describeFreshness, formatAbsoluteTimestamp } from "./freshnessFormat";

function freshness(overrides: Partial<AssetPriceFreshnessDTO> = {}): AssetPriceFreshnessDTO {
  return {
    state: "FRESH",
    staleHoldings: 0,
    unknownPriceHoldings: 0,
    missingPriceHoldings: 0,
    ...overrides,
  };
}

describe("describeFreshness", () => {
  it("is unremarkable for an all-fresh portfolio", () => {
    const result = describeFreshness(freshness());
    expect(result.severity).toBe("fresh");
  });

  it("reports the most severe state's count when stale", () => {
    const result = describeFreshness(freshness({ state: "STALE", staleHoldings: 1 }));
    expect(result.severity).toBe("attention");
    expect(result.summary).toMatch(/1 holding stale/i);
  });

  it("says so explicitly when the timestamp is absent, rather than a blank", () => {
    const result = describeFreshness(freshness({ state: "FRESH" }));
    expect(result.timestampLabel).toMatch(/no price observation on record/i);
  });

  it("uses the provided timestamp when present", () => {
    const result = describeFreshness(
      freshness({ oldestKnownAssetPriceObservationTimestamp: "2026-08-14T08:00:12Z" }),
    );
    expect(result.timestampLabel).not.toMatch(/no price observation/i);
  });
});

describe("buildFreshnessRows (requirements.md 3a)", () => {
  it("says so in one line when every count is zero (state FRESH)", () => {
    expect(buildFreshnessRows(freshness())).toEqual([{ label: "All prices fresh", count: null }]);
  });

  it("omits a state row entirely when its count is zero, never showing 0", () => {
    const rows = buildFreshnessRows(
      freshness({ state: "STALE", staleHoldings: 2, unknownPriceHoldings: 0, missingPriceHoldings: 0 }),
    );
    expect(rows).toEqual([{ label: "Stale", count: 2 }]);
  });

  it("lists every non-zero state, in Missing/Unknown/Stale order", () => {
    const rows = buildFreshnessRows(
      freshness({ state: "MISSING", staleHoldings: 1, unknownPriceHoldings: 2, missingPriceHoldings: 3 }),
    );
    expect(rows).toEqual([
      { label: "Missing", count: 3 },
      { label: "Unknown", count: 2 },
      { label: "Stale", count: 1 },
    ]);
  });
});

describe("formatAbsoluteTimestamp (requirements.md 3a — absolute, not relative)", () => {
  it("formats an ISO timestamp as an absolute date-time", () => {
    const result = formatAbsoluteTimestamp("2026-08-14T08:00:12Z");
    // Not a relative phrase like "3 days ago" — an absolute date-time string.
    expect(result).not.toMatch(/ago$/);
    expect(result).toMatch(/2026/);
  });

  it("says so explicitly when the timestamp is absent", () => {
    expect(formatAbsoluteTimestamp(undefined)).toMatch(/no price observation on record/i);
  });
});
