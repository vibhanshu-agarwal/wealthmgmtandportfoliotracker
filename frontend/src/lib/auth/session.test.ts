import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  getLoginErrorMessage,
  LoginError,
  loginWithBackend,
  signupWithBackend,
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

describe("signupWithBackend", () => {
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

  it("forwards a caller-supplied AbortSignal into the fetch() call", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => ({ token: "t", userId: "u1", email: "jane@example.com", name: "Jane Doe" }),
    } as Response);
    const controller = new AbortController();

    await signupWithBackend(
      "jane@example.com",
      "a-strong-password-123",
      "Jane Doe",
      controller.signal,
    );

    expect(mockFetch).toHaveBeenCalledOnce();
    const [, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBe(controller.signal);
  });

  // Regression test for the reviewed bug: the page's 10s AbortController.abort() must
  // actually cancel the in-flight fetch (via the signal reaching fetch()), not be a no-op
  // that leaves the request — and the "Creating account…" spinner — hanging forever.
  it("rejects with a network LoginError, in bounded time, when the request is aborted", async () => {
    mockFetch.mockImplementationOnce((_url: string, init: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => {
          reject(new DOMException("The operation was aborted", "AbortError"));
        });
      });
    });
    const controller = new AbortController();

    const pending = signupWithBackend(
      "jane@example.com",
      "a-strong-password-123",
      "Jane Doe",
      controller.signal,
    );
    controller.abort();

    await expect(pending).rejects.toMatchObject({ kind: "network" });
  });

  it("surfaces the server's 400 {error, field} body on the thrown LoginError", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => ({ error: "invalid_request", field: "password" }),
    } as Response);

    await expect(
      signupWithBackend("jane@example.com", "short", "Jane Doe"),
    ).rejects.toMatchObject({ kind: "http", status: 400, field: "password" });
  });

  it("leaves field undefined when a non-ok response has no JSON body", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => {
        throw new SyntaxError("Unexpected end of JSON input");
      },
    } as Response);

    await expect(
      signupWithBackend("jane@example.com", "a-strong-password-123", "Jane Doe"),
    ).rejects.toMatchObject({ kind: "http", status: 500, field: undefined });
  });
});