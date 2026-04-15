/* eslint-disable @typescript-eslint/no-explicit-any */
import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";

// Mock server-only since it's a server-only guard
vi.mock("server-only", () => ({}));

// Mock next/headers to return a mock Headers object
vi.mock("next/headers", () => ({
  headers: vi.fn().mockResolvedValue(new Headers()),
}));

// Mock @/lib/auth to control what auth.api.getSession returns
vi.mock("@/lib/auth", () => ({
  auth: {
    api: {
      getSession: vi.fn(),
    },
  },
}));

// Mock @/lib/auth/mintToken to return a fake JWT string
vi.mock("@/lib/auth/mintToken", () => ({
  mintToken: vi.fn().mockResolvedValue("mocked.jwt.token"),
}));

import { GET } from "./route";
import { auth } from "@/lib/auth";
import { mintToken } from "@/lib/auth/mintToken";

describe("GET /api/auth/jwt — route handler", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * Validates: Requirement 2.3
   * WHEN the Better_Auth_Session is valid, THE Token_Exchange_Endpoint SHALL
   * return a JSON response containing the `token`, `userId`, and `email` fields
   * with HTTP status 200.
   */
  it("returns 200 with { token, userId, email } for a valid session", async () => {
    const mockUser = {
      id: "user-123",
      email: "alice@example.com",
      name: "Alice",
    };

    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(200);
    expect(body).toEqual({
      token: "mocked.jwt.token",
      userId: "user-123",
      email: "alice@example.com",
    });
  });

  /**
   * Validates: Requirement 2.4
   * IF the Better_Auth_Session is missing, THEN THE Token_Exchange_Endpoint
   * SHALL return a JSON error response with HTTP status 401.
   */
  it("returns 401 when session is missing", async () => {
    vi.mocked(auth.api.getSession).mockResolvedValueOnce(null as any);

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(401);
    expect(body).toEqual({ error: "Unauthorized" });
  });

  /**
   * Validates: Requirement 2.4
   * IF the Better_Auth_Session does not contain a user ID, THEN THE
   * Token_Exchange_Endpoint SHALL return a JSON error response with HTTP status 401.
   */
  it("returns 401 when session has no user ID", async () => {
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: { id: undefined, email: "bob@example.com", name: "Bob" },
    } as any);

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(401);
    expect(body).toEqual({ error: "Unauthorized" });
  });

  it("returns 503 when session lookup throws", async () => {
    vi.mocked(auth.api.getSession).mockRejectedValueOnce(
      new Error("database connection unavailable"),
    );

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(503);
    expect(body).toEqual({
      error: "Authentication service unavailable",
      retryable: true,
    });
  });

  it("returns 503 when mintToken fails due to secret misconfiguration", async () => {
    const mockUser = {
      id: "user-789",
      email: "dana@example.com",
      name: "Dana",
    };
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);
    vi.mocked(mintToken).mockRejectedValueOnce(
      new Error("JWT signing secret is missing"),
    );

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(503);
    expect(body).toEqual({
      error: "Token service unavailable",
      retryable: true,
    });
  });

  it("returns 500 when mintToken fails unexpectedly", async () => {
    const mockUser = {
      id: "user-987",
      email: "eve@example.com",
      name: "Eve",
    };
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);
    vi.mocked(mintToken).mockRejectedValueOnce(
      new Error("unexpected signing provider failure"),
    );

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(500);
    expect(body).toEqual({
      error: "Internal token exchange failure",
    });
  });

  /**
   * Validates: Requirements 2.2, 2.5
   * THE Token_Exchange_Endpoint SHALL delegate to the Token_Issuer `mintToken`
   * function to produce the JWT. THE Token_Exchange_Endpoint SHALL NOT contain
   * any inline JWT signing logic.
   */
  it("calls mintToken to produce the JWT (not inline SignJWT)", async () => {
    const mockUser = {
      id: "user-456",
      email: "carol@example.com",
      name: "Carol",
    };

    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);

    await GET();

    expect(mintToken).toHaveBeenCalledOnce();
    expect(mintToken).toHaveBeenCalledWith(mockUser);
  });
});
