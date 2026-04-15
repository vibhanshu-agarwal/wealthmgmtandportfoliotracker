import "server-only";

import { auth } from "@/lib/auth";
import { mintToken } from "@/lib/auth/mintToken";
import { headers } from "next/headers";

export async function fetchWithAuth<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  let token: string | undefined;
  if (session?.user?.id) {
    token = await mintToken(session.user);
  }

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
