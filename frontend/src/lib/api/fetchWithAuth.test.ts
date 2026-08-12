/* eslint-disable @typescript-eslint/no-explicit-any */
import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchWithAuthClient, RateLimitError } from "./fetchWithAuth";

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

  it("clears session and throws on 401 response", async () => {
    // Mock localStorage for the clearAuthSession call
    const removeItemSpy = vi.fn();
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: removeItemSpy,
    });

    mockFetch.mockResolvedValueOnce({ ok: false, status: 401 });

    await expect(
      fetchWithAuthClient("/api/portfolio", "bad-token"),
    ).rejects.toThrow("Session expired");

    expect(removeItemSpy).toHaveBeenCalledWith("wmpt.auth.session");
  });

  it("throws an Error on 5xx response", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 });

    await expect(
      fetchWithAuthClient("/api/portfolio", "token"),
    ).rejects.toThrow("Request failed (500)");
  });

  it("throws a distinguishable RateLimitError on 429 response, carrying parsed Retry-After", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: (name: string) => (name === "Retry-After" ? "6" : null) },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect(caught).toBeInstanceOf(RateLimitError);
    expect((caught as RateLimitError).retryAfterSeconds).toBe(6);
  });

  it("does not clear the session or redirect on 429 (unlike 401)", async () => {
    const removeItemSpy = vi.fn();
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: removeItemSpy,
    });
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => null },
    });

    await expect(
      fetchWithAuthClient("/api/chat", "token"),
    ).rejects.toBeInstanceOf(RateLimitError);

    expect(removeItemSpy).not.toHaveBeenCalled();
  });

  it("resolves retryAfterSeconds to null when the Retry-After header is absent", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => null },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect((caught as RateLimitError).retryAfterSeconds).toBeNull();
  });

  it("resolves retryAfterSeconds to null when the Retry-After header is unparseable", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => "not-a-number" },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect((caught as RateLimitError).retryAfterSeconds).toBeNull();
  });

  it("resolves retryAfterSeconds to null when the Retry-After header is negative", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => "-1" },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect((caught as RateLimitError).retryAfterSeconds).toBeNull();
  });

  it("resolves retryAfterSeconds to null when the Retry-After header is an empty string", async () => {
    // Regression guard: Number("") === 0, so a naive Number() parse would wrongly treat an
    // empty header value as a valid "retry in 0 seconds" instead of "absent/invalid".
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => "" },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect((caught as RateLimitError).retryAfterSeconds).toBeNull();
  });

  it("resolves retryAfterSeconds to null for a decimal Retry-After value", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      headers: { get: () => "1.5" },
    });

    let caught: unknown;
    try {
      await fetchWithAuthClient("/api/chat", "token");
    } catch (err) {
      caught = err;
    }

    expect((caught as RateLimitError).retryAfterSeconds).toBeNull();
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
