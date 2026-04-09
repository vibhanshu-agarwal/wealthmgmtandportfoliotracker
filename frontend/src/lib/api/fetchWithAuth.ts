import { auth } from "@/auth";

/**
 * Server-side authenticated fetch.
 * Calls auth() to retrieve the current session and attaches the raw JWS as
 * Authorization: Bearer. Use in Server Components and Route Handlers.
 */
export async function fetchWithAuth<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const session = await auth();
  const token = (session as { accessToken?: string } | null)?.accessToken;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  const response = await fetch(path, {
    method: "GET",
    ...init,
    headers,
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}

/**
 * Client-side authenticated fetch.
 * Accepts the raw JWT string from useSession().data.accessToken.
 * Use in TanStack Query queryFn callbacks running in Client Components,
 * where auth() cannot be called.
 */
export async function fetchWithAuthClient<T>(
  path: string,
  token: string,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(path, {
    method: "GET",
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers as Record<string, string> | undefined),
      Authorization: `Bearer ${token}`,
    },
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}
