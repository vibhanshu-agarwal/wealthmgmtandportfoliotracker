import "server-only";

import { auth } from "@/lib/auth";
import { headers } from "next/headers";

/**
 * Server-side authenticated fetch.
 * Calls auth.api.getSession() to retrieve the current session and attaches
 * the JWT as Authorization: Bearer. Use in Server Components and Route Handlers only.
 */
export async function fetchWithAuth<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const session = await auth.api.getSession({
    headers: await headers(),
  });
  const token = session?.session?.token;

  const reqHeaders: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  const response = await fetch(path, {
    method: "GET",
    ...init,
    headers: reqHeaders,
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}
