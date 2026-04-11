/* eslint-disable @typescript-eslint/no-explicit-any */
import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchWithAuthClient } from "./fetchWithAuth";

// Mock @/lib/auth to prevent Better Auth from trying to load server modules in jsdom
vi.mock("@/lib/auth", () => ({
  auth: {
    api: {
      getSession: vi.fn().mockResolvedValue({
        session: { token: "mock-jwt" },
        user: { id: "user-001" },
      }),
    },
  },
}));

// Mock server-only since tests run in jsdom
vi.mock("server-only", () => ({}));

// Mock next/headers since it's a server-only module
vi.mock("next/headers", () => ({
  headers: vi.fn().mockResolvedValue(new Headers()),
}));

describe("fetchWithAuthClient", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("attaches Authorization: Bearer <token> header on every call", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    await fetchWithAuthClient("/api/portfolio", "my.jwt.token");

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["Authorization"]).toBe(
      "Bearer my.jwt.token",
    );
  });

  it("returns parsed JSON on 200 OK", async () => {
    const payload = { portfolioId: "p-001", holdings: [] };
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => payload,
    });

    const result = await fetchWithAuthClient("/api/portfolio", "token");

    expect(result).toEqual(payload);
  });

  it("throws an Error on 4xx response", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 401 });

    await expect(
      fetchWithAuthClient("/api/portfolio", "bad-token"),
    ).rejects.toThrow("Request failed (401)");
  });

  it("throws an Error on 5xx response", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 });

    await expect(
      fetchWithAuthClient("/api/portfolio", "token"),
    ).rejects.toThrow("Request failed (500)");
  });

  it("passes through additional RequestInit options", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({}),
    });

    await fetchWithAuthClient("/api/portfolio", "token", {
      method: "POST",
      body: JSON.stringify({ test: true }),
    });

    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ test: true }));
  });

  it("always includes Content-Type: application/json", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({}),
    });

    await fetchWithAuthClient("/api/portfolio", "token");

    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe(
      "application/json",
    );
  });
});

describe("fetchWithAuth (server-side)", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("attaches Bearer token from auth.api.getSession", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    // Dynamic import to pick up the mocks
    const { fetchWithAuth } = await import("./fetchWithAuth.server");
    await fetchWithAuth("/api/portfolio");

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["Authorization"]).toBe(
      "Bearer mock-jwt",
    );
  });

  it("omits Authorization header when no session exists", async () => {
    // Override the mock for this test to return null session
    const { auth } = await import("@/lib/auth");
    vi.mocked(auth.api.getSession).mockResolvedValueOnce(null as any);

    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    const { fetchWithAuth } = await import("./fetchWithAuth.server");
    await fetchWithAuth("/api/portfolio");

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(
      (init.headers as Record<string, string>)["Authorization"],
    ).toBeUndefined();
  });
});
