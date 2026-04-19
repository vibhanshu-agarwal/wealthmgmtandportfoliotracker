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

// Mock mintToken for server-side fetch helper tests
vi.mock("@/lib/auth/mintToken", () => ({
  mintToken: vi.fn().mockResolvedValue("mocked-jwt-from-mintToken"),
}));

describe("fetchWithAuth (server-side)", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("attaches Authorization: Bearer header from mintToken result when session exists", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    const { fetchWithAuth } = await import("./fetchWithAuth.server");
    await fetchWithAuth("/api/portfolio");

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    const authHeader = (init.headers as Record<string, string>)[
      "Authorization"
    ];
    expect(authHeader).toBe("Bearer mocked-jwt-from-mintToken");
  });

  it("omits Authorization header when no session exists", async () => {
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

  it("calls mintToken with the session user", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    const { mintToken } = await import("@/lib/auth/mintToken");
    const { fetchWithAuth } = await import("./fetchWithAuth.server");
    await fetchWithAuth("/api/portfolio");

    expect(mintToken).toHaveBeenCalledOnce();
    expect(mintToken).toHaveBeenCalledWith(
      expect.objectContaining({ id: "user-001" }),
    );
  });

  it("does not forward Cookie headers to backend (only Content-Type and Authorization)", async () => {
    // Simulate incoming request headers that include a Cookie
    const { headers: mockHeaders } = await import("next/headers");
    const incomingHeaders = new Headers({ Cookie: "session=abc123" });
    vi.mocked(mockHeaders).mockResolvedValueOnce(incomingHeaders as any);

    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: "ok" }),
    });

    const { fetchWithAuth } = await import("./fetchWithAuth.server");
    await fetchWithAuth("/api/portfolio");

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    const outgoingHeaders = init.headers as Record<string, string>;

    // Cookie must NOT be forwarded
    expect(outgoingHeaders["Cookie"]).toBeUndefined();
    expect(outgoingHeaders["cookie"]).toBeUndefined();

    // Only Content-Type and Authorization should be present
    expect(outgoingHeaders["Content-Type"]).toBe("application/json");
    expect(outgoingHeaders["Authorization"]).toBe(
      "Bearer mocked-jwt-from-mintToken",
    );

    // Verify no unexpected headers are attached
    const headerKeys = Object.keys(outgoingHeaders);
    expect(headerKeys).toEqual(
      expect.arrayContaining(["Content-Type", "Authorization"]),
    );
    expect(headerKeys).toHaveLength(2);
  });
});
