import { renderHook } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { useAuthenticatedUserId } from "./useAuthenticatedUserId";

const mockUseAuthSession = vi.fn();
vi.mock("@/lib/auth/session", () => ({
  useAuthSession: () => mockUseAuthSession(),
}));

describe("useAuthenticatedUserId", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns authenticated when local auth session contains userId and token", () => {
    mockUseAuthSession.mockReturnValue({
      data: {
        userId: "user-001",
        token: "eyJhbGciOiJIUzI1NiJ9.gateway.jwt",
        email: "dev@localhost.local",
        name: "Dev User",
      },
      isPending: false,
    });

    const { result } = renderHook(() => useAuthenticatedUserId());
    expect(result.current.status).toBe("authenticated");
    expect(result.current.userId).toBe("user-001");
    expect(result.current.token).toBe("eyJhbGciOiJIUzI1NiJ9.gateway.jwt");
    expect(result.current.error).toBeNull();
  });

  it("returns loading while auth session is pending", () => {
    mockUseAuthSession.mockReturnValue({
      data: null,
      isPending: true,
    });

    const { result } = renderHook(() => useAuthenticatedUserId());
    expect(result.current.status).toBe("loading");
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
    expect(result.current.error).toBeNull();
  });

  it("returns unauthenticated when no auth session is available", () => {
    mockUseAuthSession.mockReturnValue({
      data: null,
      isPending: false,
    });

    const { result } = renderHook(() => useAuthenticatedUserId());
    expect(result.current.status).toBe("unauthenticated");
    expect(result.current.userId).toBe("");
    expect(result.current.token).toBe("");
    expect(result.current.error).toBeNull();
  });

  it("returns exact userId and token from local session", () => {
    const expectedUserId = "abc-123-def";
    const expectedToken = "header.payload.signature";

    mockUseAuthSession.mockReturnValue({
      data: {
        token: expectedToken,
        userId: expectedUserId,
        email: "test@example.com",
        name: "Test User",
      },
      isPending: false,
    });

    const { result } = renderHook(() => useAuthenticatedUserId());
    expect(result.current.status).toBe("authenticated");
    expect(result.current.userId).toBe(expectedUserId);
    expect(result.current.token).toBe(expectedToken);
    expect(result.current.error).toBeNull();
  });
});
