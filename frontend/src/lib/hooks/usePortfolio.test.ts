import { renderHook, waitFor } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import {
  usePortfolio,
  usePortfolioSummary,
  portfolioKeys,
  retryPolicy,
} from "./usePortfolio";
import { RateLimitError } from "@/lib/api/fetchWithAuth";

// Mock the auth hook directly — avoids the internal useQuery chain
vi.mock("./useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: vi.fn(),
}));

// Mock @/lib/auth to prevent Better Auth server modules loading in jsdom
vi.mock("@/lib/auth", () => ({
  auth: {
    api: { getSession: vi.fn().mockResolvedValue(null) },
  },
}));

import { useAuthenticatedUserId } from "./useAuthenticatedUserId";
const mockUseAuthenticatedUserId = vi.mocked(useAuthenticatedUserId);

function makeWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return React.createElement(
      QueryClientProvider,
      { client: queryClient },
      children,
    );
  };
}

describe("usePortfolio", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does NOT call /api/portfolio when unauthenticated", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "",
      token: "",
      status: "unauthenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("does NOT call /api/portfolio when loading", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "",
      token: "",
      status: "loading",
      error: null,
    });

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("calls /api/portfolio with Authorization: Bearer when authenticated", async () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("query key includes userId — distinct keys for different users", () => {
    const keyA = portfolioKeys.all("user-001");
    const keyB = portfolioKeys.all("user-002");

    expect(keyA).not.toEqual(keyB);
    expect(keyA[1]).toBe("user-001");
    expect(keyB[1]).toBe("user-002");
  });
});

describe("retryPolicy", () => {
  it("does not retry a RateLimitError (429)", () => {
    const error = new RateLimitError("Rate limit exceeded (429) for /api/portfolio", 6);

    expect(retryPolicy(0, error)).toBe(false);
  });

  it("does not retry a 503 error", () => {
    const error = new Error("Request failed (503) for /api/portfolio");

    expect(retryPolicy(0, error)).toBe(false);
  });

  it("retries a generic error exactly once", () => {
    const error = new Error("Network error");

    expect(retryPolicy(0, error)).toBe(true);
    expect(retryPolicy(1, error)).toBe(false);
  });
});

describe("usePortfolioSummary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does NOT call /api/portfolio/summary when unauthenticated", () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "",
      token: "",
      status: "unauthenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("calls /api/portfolio/summary with Bearer token when authenticated", async () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data?.totalValue).toBe(284531.42);
  });

  it("preserves the full five-field assetPriceFreshness object from the summary response", async () => {
    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const freshness = result.current.data?.assetPriceFreshness;
    expect(freshness).toBeDefined();
    expect(freshness?.state).toBe("FRESH");
    expect(typeof freshness?.staleHoldings).toBe("number");
    expect(typeof freshness?.unknownPriceHoldings).toBe("number");
    expect(typeof freshness?.missingPriceHoldings).toBe("number");
    expect(freshness?.oldestKnownAssetPriceObservationTimestamp).toMatch(/^\d{4}-/);
  });

  it("does not invent freshness data when the summary request fails", async () => {
    const { http, HttpResponse } = await import("msw");
    const { server } = await import("@/test/msw/server");
    // 503 is non-retryable under retryPolicy, so the hook settles without inventing data.
    server.use(
      http.get("/api/portfolio/summary", () =>
        HttpResponse.json({ message: "unavailable" }, { status: 503 }),
      ),
    );

    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });

  it("does not invent freshness data when the summary fetch is rejected", async () => {
    const { http, HttpResponse } = await import("msw");
    const { server } = await import("@/test/msw/server");
    server.use(http.get("/api/portfolio/summary", () => HttpResponse.error()));

    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    // Network rejection retries once under retryPolicy; wait for final settlement.
    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 5_000 });
    expect(result.current.data).toBeUndefined();
  });

  it("does not invent freshness data when the summary body is invalid JSON", async () => {
    const { http, HttpResponse } = await import("msw");
    const { server } = await import("@/test/msw/server");
    server.use(
      http.get(
        "/api/portfolio/summary",
        () =>
          new HttpResponse("<!doctype html>not json", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );

    mockUseAuthenticatedUserId.mockReturnValue({
      userId: "user-001",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      status: "authenticated",
      error: null,
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 5_000 });
    expect(result.current.data).toBeUndefined();
  });
});
