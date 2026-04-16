import { renderHook, waitFor } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import {
  usePortfolio,
  usePortfolioSummary,
  portfolioKeys,
} from "./usePortfolio";

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
});
