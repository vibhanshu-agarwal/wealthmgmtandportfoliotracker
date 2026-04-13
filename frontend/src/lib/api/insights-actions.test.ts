import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock server-only, auth, and next/headers for jsdom environment
vi.mock("server-only", () => ({}));
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
vi.mock("next/headers", () => ({
  headers: vi.fn().mockResolvedValue(new Headers()),
}));

// ── Property 9: sendChatMessage Server Action error propagation ───────────────

describe("sendChatMessage Server Action", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("returns success state with response on 200", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ response: "AAPL is trending upward." }),
    });

    const { sendChatMessage } = await import("./insights-actions");
    const formData = new FormData();
    formData.set("message", "Tell me about AAPL");

    const result = await sendChatMessage(
      { response: null, error: null, status: null },
      formData,
    );

    expect(result).toEqual({
      response: "AAPL is trending upward.",
      error: null,
      status: 200,
    });
  });

  it("returns 503 error state when backend returns 503", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 503,
    });

    const { sendChatMessage } = await import("./insights-actions");
    const formData = new FormData();
    formData.set("message", "Tell me about AAPL");

    const result = await sendChatMessage(
      { response: null, error: null, status: null },
      formData,
    );

    expect(result.status).toBe(503);
    expect(result.error).toBe(
      "AI service is temporarily unavailable. Please try again later.",
    );
    expect(result.response).toBeNull();
  });

  it("returns generic error state for non-503 failures", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
    });

    const { sendChatMessage } = await import("./insights-actions");
    const formData = new FormData();
    formData.set("message", "Tell me about AAPL");

    const result = await sendChatMessage(
      { response: null, error: null, status: null },
      formData,
    );

    expect(result.status).toBe(500);
    expect(result.error).toBe("Something went wrong. Please try again.");
    expect(result.response).toBeNull();
  });

  it("returns 400 error for empty message", async () => {
    const { sendChatMessage } = await import("./insights-actions");
    const formData = new FormData();
    formData.set("message", "   ");

    const result = await sendChatMessage(
      { response: null, error: null, status: null },
      formData,
    );

    expect(result.status).toBe(400);
    expect(result.error).toBe("Message cannot be empty.");
  });
});
