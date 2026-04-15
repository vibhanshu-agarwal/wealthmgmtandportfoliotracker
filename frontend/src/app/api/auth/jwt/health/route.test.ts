/* eslint-disable @typescript-eslint/no-explicit-any */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Mock server-only guard.
vi.mock("server-only", () => ({}));

// Mock next/headers to return request headers.
vi.mock("next/headers", () => ({
  headers: vi.fn().mockResolvedValue(new Headers()),
}));

// Mock auth session lookup.
vi.mock("@/lib/auth", () => ({
  auth: {
    api: {
      getSession: vi.fn(),
    },
  },
}));

vi.mock("jose", () => ({
  jwtVerify: vi.fn(),
}));

import { auth } from "@/lib/auth";
import * as mintTokenModule from "@/lib/auth/mintToken";
import { jwtVerify } from "jose";
import { GET } from "./route";

describe("GET /api/auth/jwt/health — route handler", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubEnv("AUTH_JWT_SECRET", "health-route-test-secret-min-32-chars!!");
    vi.spyOn(mintTokenModule, "mintToken").mockResolvedValue("mocked.jwt.token");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns 200 and all checks true for healthy session/mint/verify path", async () => {
    const mockUser = {
      id: "health-user-1",
      email: "health@example.com",
      name: "Health User",
    };
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);
    vi.mocked(jwtVerify).mockResolvedValueOnce({
      payload: { sub: "health-user-1" },
    } as any);

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(200);
    expect(body).toEqual({
      status: "ok",
      checks: {
        sessionLookup: true,
        tokenMint: true,
        tokenVerify: true,
      },
      userId: "health-user-1",
    });
  });

  it("returns 401 when session is missing", async () => {
    vi.mocked(auth.api.getSession).mockResolvedValueOnce(null as any);

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(401);
    expect(body).toEqual({ error: "Unauthorized" });
  });

  it("returns 503 when auth session lookup fails", async () => {
    vi.mocked(auth.api.getSession).mockRejectedValueOnce(
      new Error("session backend unavailable"),
    );

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(503);
    expect(body).toEqual({
      error: "Authentication service unavailable",
      retryable: true,
    });
  });

  it("returns 503 when token minting fails", async () => {
    const mockUser = {
      id: "health-user-2",
      email: "mintfail@example.com",
      name: "Mint Fail",
    };
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);
    vi.spyOn(mintTokenModule, "mintToken").mockRejectedValueOnce(
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

  it("returns 503 when minted token does not verify against active secret", async () => {
    const mockUser = {
      id: "health-user-3",
      email: "verifyfail@example.com",
      name: "Verify Fail",
    };
    vi.mocked(auth.api.getSession).mockResolvedValueOnce({
      session: { token: "session-token" },
      user: mockUser,
    } as any);
    vi.spyOn(mintTokenModule, "mintToken").mockResolvedValueOnce("invalid.token.value");
    vi.mocked(jwtVerify).mockRejectedValueOnce(new Error("signature mismatch"));

    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(503);
    expect(body).toEqual({
      error: "Token service unavailable",
      retryable: true,
    });
  });
});
