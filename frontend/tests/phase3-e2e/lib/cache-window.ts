/**
 * portfolio-service caches analytics per user (Caffeine, expireAfterWrite 30 s; see
 * CacheConfig.java) and no holdings write evicts it, so for up to 30 s after a write the
 * analytics endpoint can return the pre-write result while the uncached summary is current.
 *
 * A write inside the TTL makes the cache a possible cause of a summary/analytics disagreement,
 * not a proven one. S11 therefore also waits until any pre-write entry has expired and requires
 * the endpoints to agree again (review R4 I-4).
 */
export const ANALYTICS_CACHE_TTL_MS = 30_000;

/**
 * Both stamps come from this process's clock: a write is logged once its response has arrived
 * (at or after the server's commit) and a read is stamped when its request starts (at or before
 * the server's read). The attribution window is the bare TTL; a margin would only widen
 * misattribution. An entry's TTL starts when its result is stored, after the computation, so an
 * analytics call that read pre-write data and finished after the commit can outlive the TTL by
 * its computation time. A read in that tail is not attributed and FAILs (the safe direction).
 * The wait adds a slack for it; a longer wait is always safe (review R5 M1).
 */
const EXPIRY_WAIT_SLACK_MS = 5_000;

/** Milliseconds from the user's latest write at or before `readAtMs`; null when there is none. */
export function msSinceLastWriteBefore(writeTimesMs: readonly number[], readAtMs: number): number | null {
  const priorWrites = writeTimesMs.filter((t) => t <= readAtMs);
  return priorWrites.length === 0 ? null : readAtMs - Math.max(...priorWrites);
}

export function withinAnalyticsCacheWindow(writeTimesMs: readonly number[], readAtMs: number): boolean {
  const since = msSinceLastWriteBefore(writeTimesMs, readAtMs);
  return since !== null && since <= ANALYTICS_CACHE_TTL_MS;
}

/**
 * Epoch ms by which every analytics entry populated before the user's latest write (at or before
 * `readAtMs`) has expired; null when no write precedes the read.
 */
export function staleAnalyticsExpiresBy(writeTimesMs: readonly number[], readAtMs: number): number | null {
  const since = msSinceLastWriteBefore(writeTimesMs, readAtMs);
  return since === null ? null : readAtMs - since + ANALYTICS_CACHE_TTL_MS + EXPIRY_WAIT_SLACK_MS;
}
