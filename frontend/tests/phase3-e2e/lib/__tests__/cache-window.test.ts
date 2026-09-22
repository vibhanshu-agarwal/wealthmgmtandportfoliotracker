import { describe, expect, it } from "vitest";
import { ANALYTICS_CACHE_TTL_MS, withinAnalyticsCacheWindow } from "../cache-window";

const WRITE = Date.parse("2026-09-22T06:06:10.618Z");

describe("withinAnalyticsCacheWindow", () => {
  it("attributes a disagreement to the analytics cache when the read follows a write within the TTL", () => {
    expect(withinAnalyticsCacheWindow([WRITE], WRITE + 20_806)).toBe(true);
  });

  it("uses the most recent write before the read", () => {
    expect(withinAnalyticsCacheWindow([WRITE - 120_000, WRITE], WRITE + 5_000)).toBe(true);
  });

  it("does not attribute a read after the TTL has elapsed since the last write", () => {
    expect(withinAnalyticsCacheWindow([WRITE], WRITE + ANALYTICS_CACHE_TTL_MS + 1_001)).toBe(false);
  });

  it("does not attribute a read with no preceding write, or only later writes", () => {
    expect(withinAnalyticsCacheWindow([], WRITE)).toBe(false);
    expect(withinAnalyticsCacheWindow([WRITE + 1], WRITE)).toBe(false);
  });
});
