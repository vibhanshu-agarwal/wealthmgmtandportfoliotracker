/**
 * portfolio-service caches analytics per user (Caffeine, expireAfterWrite 30 s; see
 * CacheConfig.java) and no holdings write evicts it, so for up to 30 s after a write the
 * analytics endpoint can return the pre-write result while the uncached summary is current.
 *
 * A summary/analytics disagreement is attributed to that known defect only when the run's
 * own log shows a holdings write for the user within the TTL before the page's analytics
 * read. Any other disagreement is a real divergence and must fail.
 */
export const ANALYTICS_CACHE_TTL_MS = 30_000;
const CLOCK_MARGIN_MS = 1_000;

export function withinAnalyticsCacheWindow(writeTimesMs: readonly number[], analyticsReadAtMs: number): boolean {
  const priorWrites = writeTimesMs.filter((t) => t <= analyticsReadAtMs);
  if (priorWrites.length === 0) return false;
  const lastWrite = Math.max(...priorWrites);
  return analyticsReadAtMs - lastWrite <= ANALYTICS_CACHE_TTL_MS + CLOCK_MARGIN_MS;
}
