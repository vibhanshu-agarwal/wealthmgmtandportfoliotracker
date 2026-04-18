/**
 * Playwright Global Setup — health-check poller.
 *
 * Polls the backend stack before any E2E test runs to confirm services are
 * fully initialized. Uses a two-phase strategy:
 *
 * 1. **Deep health-check** — polls through the API Gateway to a downstream
 *    service endpoint (`/api/portfolio/health`) to confirm end-to-end routing.
 * 2. **Shallow fallback** — if the deep endpoint is unavailable after 30 s,
 *    falls back to the gateway's own `/actuator/health`.
 *
 * Aborts with a clear timeout error after a configurable total timeout
 * (default 120 s).
 */

const GATEWAY_BASE = process.env.GATEWAY_BASE_URL ?? "http://localhost:8080";
const DEEP_HEALTH_URL = `${GATEWAY_BASE}/api/portfolio/health`;
const SHALLOW_HEALTH_URL = `${GATEWAY_BASE}/actuator/health`;
const POLL_INTERVAL_MS = 2_000;
const DEEP_CHECK_TIMEOUT_MS = 30_000;
const TOTAL_TIMEOUT_MS = Number(process.env.HEALTH_CHECK_TIMEOUT_MS ?? 120_000);
const SKIP_BACKEND_HEALTH_CHECK =
  (process.env.SKIP_BACKEND_HEALTH_CHECK ?? "").toLowerCase() === "true";

function timestamp(): string {
  return new Date().toISOString();
}

async function poll(url: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
      console.log(`[${timestamp()}] GET ${url} → ${response.status}`);
      if (response.status === 200) {
        return true;
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.log(`[${timestamp()}] GET ${url} → ${message}`);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }

  return false;
}

async function globalSetup(): Promise<void> {
  if (SKIP_BACKEND_HEALTH_CHECK) {
    console.log(
      `[${timestamp()}] Gateway health poll skipped (SKIP_BACKEND_HEALTH_CHECK=true). ` +
        `Use this only for stack-less smoke; full E2E must wait for the gateway.`
    );
    return;
  }

  console.log(
    `[${timestamp()}] Health-check starting (deep: ${DEEP_HEALTH_URL}, timeout: ${TOTAL_TIMEOUT_MS}ms)`
  );

  // Phase 1: deep health-check through the gateway → portfolio-service
  const deepOk = await poll(DEEP_HEALTH_URL, Math.min(DEEP_CHECK_TIMEOUT_MS, TOTAL_TIMEOUT_MS));

  if (deepOk) {
    console.log(`[${timestamp()}] Deep health-check passed`);
    return;
  }

  console.log(
    `[${timestamp()}] Deep health-check unavailable after ${DEEP_CHECK_TIMEOUT_MS}ms, falling back to shallow`
  );

  // Phase 2: shallow fallback — gateway's own actuator health
  const remainingMs = TOTAL_TIMEOUT_MS - DEEP_CHECK_TIMEOUT_MS;
  if (remainingMs <= 0) {
    throw new Error(
      `Health-check timed out after ${TOTAL_TIMEOUT_MS}ms. ` +
        `Deep endpoint ${DEEP_HEALTH_URL} never returned 200.`
    );
  }

  const shallowOk = await poll(SHALLOW_HEALTH_URL, remainingMs);

  if (shallowOk) {
    console.log(`[${timestamp()}] Shallow health-check passed (fallback)`);
    return;
  }

  throw new Error(
    `Health-check timed out after ${TOTAL_TIMEOUT_MS}ms. ` +
      `Neither ${DEEP_HEALTH_URL} nor ${SHALLOW_HEALTH_URL} returned 200.`
  );
}

export default globalSetup;
