import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchWithAuthClient } from "./fetchWithAuth";

// Mock @/auth to prevent next-auth from trying to load next/server in jsdom
vi.mock("@/auth", () => ({
  auth: vi.fn().mockResolvedValue(null),
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
