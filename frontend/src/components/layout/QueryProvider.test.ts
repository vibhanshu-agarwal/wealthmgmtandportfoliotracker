import { describe, it, expect } from "vitest";
import { defaultQueryRetry } from "./QueryProvider";
import { RateLimitError } from "@/lib/api/fetchWithAuth";

describe("defaultQueryRetry", () => {
  it("does not retry a RateLimitError (429), regardless of failure count", () => {
    const error = new RateLimitError("Rate limit exceeded (429) for /api/x", 6);

    expect(defaultQueryRetry(0, error)).toBe(false);
    expect(defaultQueryRetry(1, error)).toBe(false);
  });

  it("does not retry a generic 4xx error message", () => {
    const error = new Error("Request failed (404) for /api/x");

    expect(defaultQueryRetry(0, error)).toBe(false);
  });

  it("does NOT suppress retries for a 500 error whose message happens to contain the digit 4", () => {
    // Regression guard: the previous `.includes("4")` gate matched any message containing
    // the digit 4 — e.g. a 500 error on a path like "/portfolio/4567" — and would wrongly
    // block retries for a real 5xx transient failure.
    const error = new Error("Request failed (500) for /api/portfolio/4567");

    expect(defaultQueryRetry(0, error)).toBe(true);
  });

  it("retries a non-4xx error up to the failure-count cap", () => {
    const error = new Error("Network error");

    expect(defaultQueryRetry(0, error)).toBe(true);
    expect(defaultQueryRetry(1, error)).toBe(true);
    expect(defaultQueryRetry(2, error)).toBe(false);
  });
});
