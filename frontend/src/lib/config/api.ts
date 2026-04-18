const API_PREFIX = "/api";
const RAW_API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL?.trim() ?? "";

function normalizeApiBaseUrl(): string {
  if (!RAW_API_BASE_URL) return "";
  const withoutTrailingSlash = RAW_API_BASE_URL.replace(/\/+$/, "");
  return withoutTrailingSlash.endsWith("/api")
    ? withoutTrailingSlash.slice(0, -4)
    : withoutTrailingSlash;
}

const API_BASE_URL = normalizeApiBaseUrl();

/**
 * Builds API paths for browser and server calls.
 *
 * - Default: relative `/api/*` for CloudFront-origin routing.
 * - Optional: absolute base via `NEXT_PUBLIC_API_BASE_URL` for E2E/local stacks.
 */
export function apiPath(path: string): string {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (API_BASE_URL) {
    return `${API_BASE_URL}${API_PREFIX}${normalized}`;
  }
  return `${API_PREFIX}${normalized}`;
}
