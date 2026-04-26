import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  getLoginErrorMessage,
  LoginError,
  loginWithBackend,
} from "./session";

describe("loginWithBackend", () => {
  const mockFetch = vi.fn();
  const storage = new Map<string, string>();

  function installLocalStorage(): void {
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      value: {
        getItem: vi.fn((key: string) => storage.get(key) ?? null),
        setItem: vi.fn((key: string, value: string) => storage.set(key, value)),
        removeItem: vi.fn((key: string) => storage.delete(key)),
        clear: vi.fn(() => storage.clear()),
      },
    });
  }

  beforeEach(() => {
    storage.clear();
    installLocalStorage();
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.clearAllMocks();
    storage.clear();
    vi.unstubAllGlobals();
  });

  it("throws status-carrying LoginError on 401", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 401 } as Response);

    await expect(loginWithBackend("demo@example.com", "wrong")).rejects.toMatchObject({
      kind: "http",
      status: 401,
    });
  });

  it("maps 401 to invalid credentials", () => {
    const message = getLoginErrorMessage(new LoginError("failed", "http", 401));
    expect(message).toBe("Invalid username or password.");
  });

  it("maps backend 500 to service unavailable", () => {
    const message = getLoginErrorMessage(new LoginError("failed", "http", 500));
    expect(message).toBe(
      "Login service is temporarily unavailable. Please try again shortly.",
    );
  });

  it("maps network failures to reachability message", async () => {
    mockFetch.mockRejectedValueOnce(new TypeError("fetch failed"));

    await expect(loginWithBackend("demo@example.com", "password")).rejects.toMatchObject({
      kind: "network",
    });
  });

  it("maps network LoginError to reachability message", () => {
    const message = getLoginErrorMessage(new LoginError("failed", "network"));
    expect(message).toBe("Unable to reach the login service. Please try again.");
  });

  it("maps malformed success responses to invalid response message", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ token: "token-only" }),
    } as Response);

    await expect(loginWithBackend("demo@example.com", "password")).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });

  it("maps invalid response LoginError to invalid response message", () => {
    const message = getLoginErrorMessage(
      new LoginError("missing fields", "invalid-response"),
    );
    expect(message).toBe("Login response was invalid. Please try again.");
  });
});