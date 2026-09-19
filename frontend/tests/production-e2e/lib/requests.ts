/**
 * Wave 10.2 Step B (exit criterion 5b) - request classification and path templating.
 *
 * A request URL is reduced to at most a path TEMPLATE from the contract's closed
 * list. The query string (which carries ticker lists and the demo user id) never
 * survives, and anything outside the list is rejected rather than recorded.
 */

export type RequestKind = "api" | "frontend" | "other";

export interface Origins {
  readonly frontend: string;
  readonly api: string;
}

export interface ClassifiedRequest {
  readonly kind: RequestKind;
  /** Upper-cased HTTP method. */
  readonly method: string;
  /** A contract path template, or null for any other path (never recorded). */
  readonly path: string | null;
  /** True for every method except GET and OPTIONS. */
  readonly isWrite: boolean;
}

/** The single place a URL string is parsed. Null when the string is not an absolute URL. */
function parseUrl(rawUrl: string): URL | null {
  try {
    return new URL(rawUrl);
  } catch {
    return null;
  }
}

export function pathnameOf(rawUrl: string): string {
  return parseUrl(rawUrl)?.pathname ?? "";
}

/**
 * True only when `rawUrl` is on exactly `origin` (scheme, host and port) AND its pathname is
 * exactly `pathname`. A parse failure is false. A pathname match alone proves nothing: a page
 * that ended up on another origin at the same path must not count as having stayed on the site.
 */
export function urlIsOnOriginAtPath(rawUrl: string, origin: string, pathname: string): boolean {
  const url = parseUrl(rawUrl);
  return url !== null && url.origin === origin && url.pathname === pathname;
}

/**
 * Non-GET/OPTIONS is a write. HEAD counts as a write on purpose (spec L7 counts
 * "non-GET/OPTIONS"), and an empty or unknown method is treated as a write so a
 * parsing surprise can only ever make the check stricter.
 */
export function isWriteMethod(method: string): boolean {
  const normalized = method.toUpperCase();
  return normalized !== "GET" && normalized !== "OPTIONS";
}

/**
 * Returns the contract path template for `rawUrl`, or null. Only URLs whose origin
 * is exactly `apiOrigin` and whose pathname is exactly one of `allowedPaths` qualify:
 * no trailing slash, no case folding, no percent-encoded separators.
 */
export function templateLedgerPath(
  rawUrl: string,
  apiOrigin: string,
  allowedPaths: readonly string[],
): string | null {
  const url = parseUrl(rawUrl);
  if (url === null || url.origin !== apiOrigin) return null;
  return allowedPaths.includes(url.pathname) ? url.pathname : null;
}

export function classifyRequest(
  method: string,
  rawUrl: string,
  origins: Origins,
  allowedPaths: readonly string[],
): ClassifiedRequest {
  const url = parseUrl(rawUrl);
  const normalizedMethod = method.toUpperCase();
  let kind: RequestKind = "other";
  if (url !== null && url.origin === origins.api) kind = "api";
  else if (url !== null && url.origin === origins.frontend) kind = "frontend";

  return {
    kind,
    method: normalizedMethod,
    path: kind === "api" ? templateLedgerPath(rawUrl, origins.api, allowedPaths) : null,
    isWrite: isWriteMethod(normalizedMethod),
  };
}
