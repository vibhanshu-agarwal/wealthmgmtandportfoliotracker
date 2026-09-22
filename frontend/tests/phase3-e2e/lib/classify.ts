/**
 * Narrow classification of the one known-noise failure class.
 *
 * Next 16's static export writes dashboard segment-prefetch files as nested directories
 * (`/portfolio/__next.!KGRhc2hib2FyZCk/portfolio.txt`), while the client requests a
 * dot-joined name (`/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.txt?_rsc=…`), which
 * 404s. `!KGRhc2hib2FyZCk` is the encoded `(dashboard)` route group. Only that exact
 * shape, on the frontend origin, with status 404 and an `_rsc` query, is noise.
 * Everything else is unexpected. Noise is counted and reported, never dropped.
 */
export type FailureClass = "next-segment-prefetch-404" | "unexpected";

const PREFETCH_PATH = /^\/([a-z0-9-]+)\/__next\.!KGRhc2hib2FyZCk\.\1(\.__PAGE__)?\.txt$/;
const RESOURCE_404_MESSAGE = "Failed to load resource: the server responded with a status of 404 (Not Found)";

function isPrefetchUrl(url: string, frontendOrigin: string): boolean {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  return parsed.origin === frontendOrigin && PREFETCH_PATH.test(parsed.pathname) && parsed.searchParams.has("_rsc");
}

const RESOURCE_FAILURE = /^Failed to load resource: the server responded with a status of (\d{3})\b/;

/** The HTTP status in the browser's "Failed to load resource" console message, else null. */
export function resourceFailureStatus(text: string): number | null {
  const match = RESOURCE_FAILURE.exec(text);
  return match ? Number(match[1]) : null;
}

export function classifyHttpFailure(url: string, status: number, frontendOrigin: string): FailureClass {
  return status === 404 && isPrefetchUrl(url, frontendOrigin) ? "next-segment-prefetch-404" : "unexpected";
}

export function classifyConsoleError(text: string, locationUrl: string, frontendOrigin: string): FailureClass {
  return text === RESOURCE_404_MESSAGE && isPrefetchUrl(locationUrl, frontendOrigin)
    ? "next-segment-prefetch-404"
    : "unexpected";
}
