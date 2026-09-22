import { describe, expect, it } from "vitest";
import { ANALYTICS_CACHE_TTL_MS, msSinceLastWriteBefore, staleAnalyticsExpiresBy, withinAnalyticsCacheWindow } from "../cache-window";

const WRITE = Date.parse("2026-09-22T06:06:10.618Z");

describe("msSinceLastWriteBefore", () => {
  it("measures from the most recent write at or before the read", () => {
    expect(msSinceLastWriteBefore([WRITE - 120_000, WRITE], WRITE + 5_000)).toBe(5_000);
    expect(msSinceLastWriteBefore([WRITE], WRITE)).toBe(0);
  });

  it("ignores writes after the read", () => {
    expect(msSinceLastWriteBefore([WRITE, WRITE + 10_000], WRITE + 5_000)).toBe(5_000);
  });

  it("is null when no write precedes the read, including when every write postdates it", () => {
    expect(msSinceLastWriteBefore([], WRITE)).toBeNull();
    expect(msSinceLastWriteBefore([WRITE + 1], WRITE)).toBeNull();
  });
});

describe("withinAnalyticsCacheWindow", () => {
  it("attributes a disagreement to the analytics cache when the read follows a write within the TTL", () => {
    expect(withinAnalyticsCacheWindow([WRITE], WRITE + 20_806)).toBe(true);
  });

  it("uses the most recent write before the read", () => {
    expect(withinAnalyticsCacheWindow([WRITE - 120_000, WRITE], WRITE + 5_000)).toBe(true);
  });

  it("uses the bare TTL, with no margin (both stamps already bracket the server's write and read)", () => {
    expect(withinAnalyticsCacheWindow([WRITE], WRITE + ANALYTICS_CACHE_TTL_MS)).toBe(true);
    expect(withinAnalyticsCacheWindow([WRITE], WRITE + ANALYTICS_CACHE_TTL_MS + 1)).toBe(false);
  });

  it("does not attribute a read with no preceding write, or only later writes", () => {
    expect(withinAnalyticsCacheWindow([], WRITE)).toBe(false);
    expect(withinAnalyticsCacheWindow([WRITE + 1], WRITE)).toBe(false);
  });
});

describe("staleAnalyticsExpiresBy", () => {
  it("is later than the TTL after the most recent write before the read", () => {
    const expiry = staleAnalyticsExpiresBy([WRITE - 120_000, WRITE, WRITE + 60_000], WRITE + 5_000);
    expect(expiry).not.toBeNull();
    expect(expiry!).toBeGreaterThan(WRITE + ANALYTICS_CACHE_TTL_MS);
    expect(expiry!).toBeLessThanOrEqual(WRITE + ANALYTICS_CACHE_TTL_MS + 2_000);
  });

  it("is null when no write precedes the read", () => {
    expect(staleAnalyticsExpiresBy([], WRITE)).toBeNull();
    expect(staleAnalyticsExpiresBy([WRITE + 1], WRITE)).toBeNull();
  });
});
