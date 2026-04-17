const API_PREFIX = "/api";

/**
 * Enforces relative API paths so CloudFront can route `/api/*`.
 */
export function apiPath(path: string): string {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return `${API_PREFIX}${normalized}`;
}
