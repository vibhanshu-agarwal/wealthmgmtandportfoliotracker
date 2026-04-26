import path from "node:path";
import fs from "node:fs";

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

const GATEWAY_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const DEEP_HEALTH_URL = `${GATEWAY_BASE}/api/portfolio/health`;
const SHALLOW_HEALTH_URL = `${GATEWAY_BASE}/actuator/health`;

let INTERNAL_API_KEY = process.env.INTERNAL_API_KEY || process.env.TF_VAR_internal_api_key;

// Fallback: manually parse .env.secrets if key is missing (common for local runs without dotenv)
if (!INTERNAL_API_KEY) {
  const secretsPath = path.resolve(__dirname, "../../../.env.secrets");
  if (fs.existsSync(secretsPath)) {
    const secrets = fs.readFileSync(secretsPath, "utf-8");
    const match = secrets.match(/^TF_VAR_internal_api_key=(.*)$/m);
    if (match) {
      INTERNAL_API_KEY = match[1].trim();
    }
  }
}

const TEST_USER_ID = process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";

const POLL_INTERVAL_MS = 2_000;
const DEEP_CHECK_TIMEOUT_MS = 30_000;
const TOTAL_TIMEOUT_MS = Number(process.env.HEALTH_CHECK_TIMEOUT_MS ?? 120_000);
const SKIP_BACKEND_HEALTH_CHECK =
  (process.env.SKIP_BACKEND_HEALTH_CHECK ?? "").toLowerCase() === "true";
const IS_LOCAL_GATEWAY = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(GATEWAY_BASE);
const SEED_MAX_RETRIES = Number(process.env.SEED_MAX_RETRIES ?? (IS_LOCAL_GATEWAY ? 3 : 8));
const SEED_RETRY_DELAY_MS = Number(process.env.SEED_RETRY_DELAY_MS ?? (IS_LOCAL_GATEWAY ? 5_000 : 10_000));
const SEED_REQUEST_TIMEOUT_MS = Number(process.env.SEED_REQUEST_TIMEOUT_MS ?? 70_000);
const SEED_WARMUP_TIMEOUT_MS = Number(process.env.SEED_WARMUP_TIMEOUT_MS ?? (IS_LOCAL_GATEWAY ? 10_000 : 60_000));
const SEED_WARMUP_PATHS = ["/api/portfolio/health", "/api/market/health", "/api/insights/health"];

function timestamp(): string {
  return new Date().toISOString();
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isTransientSeedStatus(status: number, body: string): boolean {
  if (status === 503 && body.includes("internal_api_key_not_configured")) {
    return false;
  }
  return [429, 500, 502, 503, 504].includes(status);
}

async function safeResponseText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch (error) {
    return `<failed to read response body: ${error instanceof Error ? error.message : String(error)}>`;
  }
}

async function warmSeedDependencies(): Promise<void> {
  console.log(`[${timestamp()}] Warming backend seed dependencies via gateway health endpoints...`);
  for (const path of SEED_WARMUP_PATHS) {
    const ok = await poll(`${GATEWAY_BASE}${path}`, SEED_WARMUP_TIMEOUT_MS);
    if (!ok) {
      console.warn(
        `[${timestamp()}] Warm-up warning: ${path} did not return 200 within ${SEED_WARMUP_TIMEOUT_MS}ms. ` +
          `Continuing because seed retries still handle Lambda cold starts.`,
      );
    }
  }
}

/**
 * Fetch a seeding endpoint with automatic retry on 502/504 (Lambda cold-start gateway errors).
 * CloudFront's 60s origin timeout fires if the combined cold-start chain (api-gateway + downstream
 * Lambda) exceeds 60s. The first 504 proves the Lambda is booting — waiting 5s then retrying
 * hits the now-warm instance. maxRetries=3 gives ~3×65s budget before giving up.
 */
async function seedFetch(
  label: string,
  url: string,
  body: object,
  maxRetries = SEED_MAX_RETRIES,
): Promise<Response> {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Internal-Api-Key": INTERNAL_API_KEY!,
        },
        body: JSON.stringify(body),
        // 70s allows a full Lambda cold start (~30s) plus processing headroom.
        // CloudFront returns 504 at 60s if the origin doesn't respond in time;
        // we retry that rather than treating it as a hard failure.
        signal: AbortSignal.timeout(SEED_REQUEST_TIMEOUT_MS),
      });

      const responseBody = res.ok ? "" : await safeResponseText(res.clone());
      if (isTransientSeedStatus(res.status, responseBody) && attempt < maxRetries) {
        console.log(
          `[${timestamp()}] ${label}: HTTP ${res.status} on attempt ${attempt}/${maxRetries} ` +
            `(transient Lambda/API Gateway response) — retrying in ${SEED_RETRY_DELAY_MS}ms...`,
        );
        await sleep(SEED_RETRY_DELAY_MS);
        continue;
      }

      return res;
    } catch (error) {
      if (attempt >= maxRetries) {
        throw error;
      }
      console.log(
        `[${timestamp()}] ${label}: request failed on attempt ${attempt}/${maxRetries}: ` +
          `${error instanceof Error ? error.message : String(error)} — retrying in ${SEED_RETRY_DELAY_MS}ms...`,
      );
      await sleep(SEED_RETRY_DELAY_MS);
    }
  }
  // Should never reach here — the loop always returns or continues.
  throw new Error(`${label}: exhausted ${maxRetries} retries`);
}

async function runSeeding(): Promise<void> {
  if (!INTERNAL_API_KEY) {
    console.warn(`[${timestamp()}] Skipping Golden State seeding: INTERNAL_API_KEY not set.`);
    return;
  }

  console.log(`[${timestamp()}] Starting Golden State seeding for ${TEST_USER_ID}...`);

  try {
    await warmSeedDependencies();

    // 1. Portfolio Seeding -> Get portfolioId
    const portfolioRes = await seedFetch(
      "Portfolio seeding",
      `${GATEWAY_BASE}/api/internal/portfolio/seed`,
      { userId: TEST_USER_ID },
    );

    if (!portfolioRes.ok) {
      throw new Error(`Portfolio seeding failed: ${portfolioRes.status} ${await safeResponseText(portfolioRes)}`);
    }

    const { portfolioId } = await portfolioRes.json();
    console.log(`[${timestamp()}] Portfolio seeded. ID: ${portfolioId}`);

    // 2. Market Data Seeding
    const marketRes = await seedFetch(
      "Market data seeding",
      `${GATEWAY_BASE}/api/internal/market-data/seed`,
      { userId: TEST_USER_ID },
    );

    if (!marketRes.ok) {
      throw new Error(`Market data seeding failed: ${marketRes.status} ${await safeResponseText(marketRes)}`);
    }
    console.log(`[${timestamp()}] Market data seeded.`);

    // 3. Insight Seeding (Cache Eviction)
    const insightRes = await seedFetch(
      "Insight seeding",
      `${GATEWAY_BASE}/api/internal/insight/seed`,
      { userId: TEST_USER_ID, portfolioId },
    );

    if (!insightRes.ok) {
      throw new Error(`Insight seeding failed: ${insightRes.status} ${await safeResponseText(insightRes)}`);
    }
    console.log(`[${timestamp()}] Insight cache evicted. Seeding complete.`);
  } catch (error) {
    console.error(`[${timestamp()}] Seeding ERROR:`, error);
    throw error; // Fail the entire test run if seeding fails
  }
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
  // Phase 0: Run seeding if applicable
  await runSeeding();

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
