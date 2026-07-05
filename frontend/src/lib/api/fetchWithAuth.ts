import { clearAuthSession } from "@/lib/auth/session";

/**
 * Thrown when the gateway responds with 429 (rate limit exceeded).
 *
 * Deliberately a distinct error type from the generic request-failure `Error`
 * thrown for other non-2xx statuses (Req 6.2, 6.3): callers must be able to
 * tell "you're being rate limited" apart from "something else failed" without
 * parsing the message string, and must NOT clear the session or redirect the
 * way a 401 does (Req 6.5) — a 429 is a client-pacing problem, not an auth
 * failure.
 */
export class RateLimitError extends Error {
  constructor(
    message: string,
    readonly retryAfterSeconds: number | null,
  ) {
    super(message);
    this.name = "RateLimitError";
  }
}

/**
 * Parses the `Retry-After` header as a non-negative integer number of seconds.
 * Returns null when the header is absent or does not parse to a non-negative
 * integer, so callers can fall back to a sane default rather than propagating
 * `NaN` or a negative countdown into the UI.
 */
function parseRetryAfterSeconds(headerValue: string | null): number | null {
  if (headerValue === null) return null;
  const seconds = Number(headerValue);
  if (!Number.isFinite(seconds) || !Number.isInteger(seconds) || seconds < 0) {
    return null;
  }
  return seconds;
}

/**
 * Client-side authenticated fetch.
 * Accepts the raw JWT string from useSession().data.session.token.
 * Use in TanStack Query queryFn callbacks running in Client Components.
 *
 * On 401 Unauthorized, clears the stored session and redirects to /login
 * so stale/expired tokens don't produce cascading console errors.
 *
 * On 429 Too Many Requests, throws a {@link RateLimitError} carrying the
 * parsed `Retry-After` seconds (or null if absent/unparseable) instead of
 * clearing the session or redirecting.
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

  if (response.status === 429) {
    throw new RateLimitError(
      `Rate limit exceeded (429) for ${path}`,
      parseRetryAfterSeconds(response.headers.get("Retry-After")),
    );
  }

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}
