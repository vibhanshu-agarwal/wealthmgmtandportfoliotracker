import { renderHook, waitFor } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { useSession } from "next-auth/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import {
  usePortfolio,
  usePortfolioSummary,
  portfolioKeys,
} from "./usePortfolio";

// Mock @/auth to prevent next-auth from trying to load next/server in jsdom
vi.mock("@/auth", () => ({
  auth: vi.fn().mockResolvedValue(null),
}));

vi.mock("next-auth/react", () => ({
  useSession: vi.fn(),
}));

const mockUseSession = vi.mocked(useSession);

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

const AUTHENTICATED_SESSION = {
  data: {
    user: { id: "user-001", name: "Dev User", email: "dev@local" },
    accessToken: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
    expires: new Date(Date.now() + 3_600_000).toISOString(),
  },
  status: "authenticated" as const,
  update: vi.fn(),
};

describe("usePortfolio", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does NOT call /api/portfolio when status is unauthenticated", () => {
    mockUseSession.mockReturnValue({
      data: null,
      status: "unauthenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    // Query should be disabled — fetchStatus is "idle", not "fetching"
    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("does NOT call /api/portfolio when status is loading", () => {
    mockUseSession.mockReturnValue({
      data: null,
      status: "loading",
      update: vi.fn(),
    });

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("calls /api/portfolio with Authorization: Bearer when authenticated", async () => {
    mockUseSession.mockReturnValue(AUTHENTICATED_SESSION);

    const { result } = renderHook(() => usePortfolio(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // MSW handler returns a portfolio array; fetchPortfolio returns the first item
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
    mockUseSession.mockReturnValue({
      data: null,
      status: "unauthenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("calls /api/portfolio/summary with Bearer token when authenticated", async () => {
    mockUseSession.mockReturnValue(AUTHENTICATED_SESSION);

    const { result } = renderHook(() => usePortfolioSummary(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toBeDefined();
    expect(result.current.data?.totalValue).toBe(284531.42);
  });
});
