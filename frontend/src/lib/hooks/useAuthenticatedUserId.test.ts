import { renderHook, waitFor } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import { useAuthenticatedUserId } from "./useAuthenticatedUserId";

const mockFetch = vi.fn();

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

describe("useAuthenticatedUserId", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns authenticated with userId and JWT when BFF returns 200", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        token: "eyJhbGciOiJIUzI1NiJ9.gateway.jwt",
        userId: "user-001",
        email: "dev@localhost.local",
      }),
    });

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    expect(result.current.userId).toBe("user-001");
    expect(result.current.token).toBe("eyJhbGciOiJIUzI1NiJ9.gateway.jwt");
    expect(result.current.error).toBeNull();
  });

  it("returns loading while BFF request is in flight", () => {
    mockFetch.mockReturnValue(new Promise(() => {})); // never resolves

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    expect(result.current.status).toBe("loading");
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
    expect(result.current.error).toBeNull();
  });

  it("returns unauthenticated when BFF returns 401", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => ({ error: "Unauthorized" }),
    });

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.status).toBe("unauthenticated"));
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
    expect(result.current.error).toBeNull();
  });

  it("calls /api/auth/jwt with credentials: include", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        token: "jwt",
        userId: "user-001",
        email: "dev@localhost.local",
      }),
    });

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    expect(mockFetch).toHaveBeenCalledWith("/api/auth/jwt", {
      credentials: "include",
    });
  });

  it("userId and token match the BFF response exactly", async () => {
    const expectedUserId = "abc-123-def";
    const expectedToken = "header.payload.signature";

    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        token: expectedToken,
        userId: expectedUserId,
        email: "test@example.com",
      }),
    });

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.status).toBe("authenticated"));
    expect(result.current.userId).toBe(expectedUserId);
    expect(result.current.token).toBe(expectedToken);
    expect(result.current.error).toBeNull();
  });

  it("returns error status when JWT exchange endpoint fails (e.g. 503)", async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({ error: "Token service unavailable", retryable: true }),
    });

    const { result } = renderHook(() => useAuthenticatedUserId(), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.status).toBe("error"), {
      timeout: 4000,
    });
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
    expect(result.current.error).toBe("JWT exchange failed (503)");
  });
});
