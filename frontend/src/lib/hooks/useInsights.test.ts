import { describe, it, expect, vi } from "vitest";
import { retryPolicy } from "./useInsights";
import { RateLimitError } from "@/lib/api/fetchWithAuth";

// Mock @/lib/auth to prevent Better Auth server modules loading in jsdom,
// consistent with usePortfolio.test.ts.
vi.mock("@/lib/auth", () => ({
  auth: {
    api: { getSession: vi.fn().mockResolvedValue(null) },
  },
}));

describe("retryPolicy", () => {
  it("does not retry a RateLimitError (429)", () => {
    const error = new RateLimitError("Rate limit exceeded (429) for /api/insights/market-summary", 6);

    expect(retryPolicy(0, error)).toBe(false);
  });

  it("does not retry a 503 error", () => {
    const error = new Error("Request failed (503) for /api/insights/market-summary");

    expect(retryPolicy(0, error)).toBe(false);
  });

  it("retries a generic error exactly once", () => {
    const error = new Error("Network error");

    expect(retryPolicy(0, error)).toBe(true);
    expect(retryPolicy(1, error)).toBe(false);
  });

  it("retries a non-Error value exactly once", () => {
    expect(retryPolicy(0, "some string error")).toBe(true);
    expect(retryPolicy(1, "some string error")).toBe(false);
  });
});
