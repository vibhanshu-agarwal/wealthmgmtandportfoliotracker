"use client";

import { useAuthSession } from "@/lib/auth/session";

export interface AuthenticatedUser {
  userId: string;
  token: string;
  status: "authenticated" | "loading" | "unauthenticated" | "error";
  error: string | null;
}

/**
 * Returns the authenticated user's ID and JWT from browser-local session state.
 */
export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data, isPending } = useAuthSession();

  if (isPending) {
    return { userId: "", token: "", status: "loading", error: null };
  }

  if (data?.token && data?.userId) {
    return {
      userId: data.userId,
      token: data.token,
      status: "authenticated",
      error: null,
    };
  }

  return { userId: "", token: "", status: "unauthenticated", error: null };
}
