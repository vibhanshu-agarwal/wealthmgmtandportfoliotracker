"use client";

import { useSession } from "next-auth/react";

export interface AuthenticatedUser {
  userId: string;
  token: string;
  status: "authenticated" | "loading" | "unauthenticated";
}

/**
 * Returns the authenticated user's ID (sub claim) and raw JWT token.
 *
 * - status === "loading"         → session is being fetched; do not issue API requests
 * - status === "unauthenticated" → no session; middleware will redirect to /login
 * - status === "authenticated"   → userId and token are safe to use
 */
export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data: session, status } = useSession();

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

  return { userId: "", token: "", status };
}
