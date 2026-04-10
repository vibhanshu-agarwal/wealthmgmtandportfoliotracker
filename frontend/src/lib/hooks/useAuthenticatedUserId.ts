"use client";

import { useSession } from "next-auth/react";

export interface AuthenticatedUser {
  userId: string;
  token: string;
  status: "authenticated" | "loading" | "unauthenticated";
}

/**
 * Reads the raw JWT from the authjs.session-token cookie synchronously.
 * This avoids the async /api/auth/session round-trip that causes TanStack Query
 * hooks to fire with an empty token on first render (hydration-no-flicker pattern).
 *
 * Returns null if the cookie is absent or cannot be parsed.
 */
function readTokenFromCookie(): { sub: string; token: string } | null {
  if (typeof document === "undefined") return null;
  try {
    const cookieName = "authjs.session-token";
    const match = document.cookie
      .split("; ")
      .find((row) => row.startsWith(`${cookieName}=`));
    if (!match) return null;

    const raw = match.split("=").slice(1).join("=");
    // JWT is header.payload.sig — decode the payload
    const parts = raw.split(".");
    if (parts.length !== 3) return null;

    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
    const sub = payload?.sub as string | undefined;
    if (!sub) return null;

    return { sub, token: raw };
  } catch {
    return null;
  }
}

/**
 * Returns the authenticated user's ID (sub claim) and raw JWT token.
 *
 * Uses a two-tier approach per the hydration-no-flicker rule:
 * 1. Reads the JWT cookie synchronously on first render (no loading flash)
 * 2. Falls back to useSession() for the full session object (userId, accessToken)
 *
 * This ensures TanStack Query hooks fire immediately with a valid token
 * rather than waiting for the async /api/auth/session round-trip.
 */
export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data: session, status } = useSession();

  // Tier 1: useSession() has resolved — use the full session object
  if (
    status === "authenticated" &&
    session?.user?.id &&
    session?.accessToken
  ) {
    return {
      userId: session.user.id,
      token: session.accessToken,
      status: "authenticated",
    };
  }

  // Tier 2: useSession() is still loading — read the cookie synchronously
  // so queries can fire immediately without waiting for the session round-trip.
  if (status === "loading") {
    const cookieData = readTokenFromCookie();
    if (cookieData) {
      return {
        userId: cookieData.sub,
        token: cookieData.token,
        status: "authenticated",
      };
    }
  }

  return { userId: "", token: "", status };
}
