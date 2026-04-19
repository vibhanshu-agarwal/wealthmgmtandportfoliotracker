import { clearAuthSession } from "@/lib/auth/session";

/**
 * Client-side authenticated fetch.
 * Accepts the raw JWT string from useSession().data.session.token.
 * Use in TanStack Query queryFn callbacks running in Client Components.
 *
 * On 401 Unauthorized, clears the stored session and redirects to /login
 * so stale/expired tokens don't produce cascading console errors.
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

  if (response.status === 401) {
    clearAuthSession();
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.href = "/login";
    }
    throw new Error("Session expired");
  }

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}
