"use client";

import { useQuery } from "@tanstack/react-query";

export interface AuthenticatedUser {
  userId: string;
  token: string;
  status: "authenticated" | "loading" | "unauthenticated";
}

interface GatewayJwtResponse {
  token: string;
  userId: string;
  email: string;
}

/**
 * Fetches the HS256 JWT and user info from the BFF token exchange endpoint.
 * Returns null if the user is not authenticated (401).
 */
async function fetchGatewayJwt(): Promise<GatewayJwtResponse | null> {
  const res = await fetch("/api/auth/jwt", { credentials: "include" });
  if (res.status === 401) return null;
  if (!res.ok) throw new Error(`JWT exchange failed (${res.status})`);
  return res.json();
}

/**
 * Returns the authenticated user's ID and an HS256 JWT for the API Gateway.
 *
 * Single async call to the BFF /api/auth/jwt endpoint — no dependency on
 * Better Auth's useSession() client hook. The BFF reads the session cookie
 * server-side and returns both the signed JWT and the userId in one response.
 *
 * This eliminates the two-step async chain (useSession → useQuery) that
 * caused race conditions where downstream TanStack Query hooks stayed disabled.
 */
export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data, isLoading } = useQuery({
    queryKey: ["gateway-jwt"],
    queryFn: fetchGatewayJwt,
    staleTime: 50 * 60 * 1000, // 50 minutes (JWT expires in 1h)
    refetchInterval: 50 * 60 * 1000,
    retry: 1,
    refetchOnWindowFocus: false,
  });

  if (isLoading) {
    return { userId: "", token: "", status: "loading" };
  }

  if (data?.token && data?.userId) {
    return {
      userId: data.userId,
      token: data.token,
      status: "authenticated",
    };
  }

  return { userId: "", token: "", status: "unauthenticated" };
}
