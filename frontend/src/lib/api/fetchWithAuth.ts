/**
 * Client-side authenticated fetch.
 * Accepts the raw JWT string from useSession().data.session.token.
 * Use in TanStack Query queryFn callbacks running in Client Components.
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
