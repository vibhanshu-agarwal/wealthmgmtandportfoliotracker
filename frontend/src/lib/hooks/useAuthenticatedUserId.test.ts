import { renderHook } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { useSession } from "next-auth/react";
import { useAuthenticatedUserId } from "./useAuthenticatedUserId";

vi.mock("next-auth/react", () => ({
  useSession: vi.fn(),
}));

const mockUseSession = vi.mocked(useSession);

describe("useAuthenticatedUserId", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns authenticated state with userId and token when session is active", () => {
    mockUseSession.mockReturnValue({
      data: {
        user: { id: "user-001", name: "Dev User", email: "dev@local" },
        accessToken: "eyJhbGciOiJIUzI1NiJ9.test.sig",
        expires: new Date(Date.now() + 3_600_000).toISOString(),
      },
      status: "authenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    expect(result.current.status).toBe("authenticated");
    expect(result.current.userId).toBe("user-001");
    expect(result.current.token).toBe("eyJhbGciOiJIUzI1NiJ9.test.sig");
  });

  it("returns loading state when session is being fetched", () => {
    mockUseSession.mockReturnValue({
      data: null,
      status: "loading",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    expect(result.current.status).toBe("loading");
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
  });

  it("returns unauthenticated state when no session exists", () => {
    mockUseSession.mockReturnValue({
      data: null,
      status: "unauthenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    expect(result.current.status).toBe("unauthenticated");
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
  });

  it("userId equals session.user.id (sub claim round-trip)", () => {
    const expectedId = "user-abc-123";
    mockUseSession.mockReturnValue({
      data: {
        user: { id: expectedId, name: "Test", email: "test@local" },
        accessToken: "some.jwt.token",
        expires: new Date(Date.now() + 3_600_000).toISOString(),
      },
      status: "authenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    expect(result.current.userId).toBe(expectedId);
  });

  it("token equals session.accessToken", () => {
    const expectedToken = "header.payload.signature";
    mockUseSession.mockReturnValue({
      data: {
        user: { id: "user-001", name: "Dev User", email: "dev@local" },
        accessToken: expectedToken,
        expires: new Date(Date.now() + 3_600_000).toISOString(),
      },
      status: "authenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    expect(result.current.token).toBe(expectedToken);
  });

  it("returns unauthenticated when session exists but accessToken is missing", () => {
    mockUseSession.mockReturnValue({
      data: {
        user: { id: "user-001", name: "Dev User", email: "dev@local" },
        // accessToken intentionally absent
        expires: new Date(Date.now() + 3_600_000).toISOString(),
      },
      status: "authenticated",
      update: vi.fn(),
    });

    const { result } = renderHook(() => useAuthenticatedUserId());

    // Without accessToken we cannot attach a Bearer header — treat as not ready
    expect(result.current.token).toBe("");
    expect(result.current.userId).toBe("");
  });
});
