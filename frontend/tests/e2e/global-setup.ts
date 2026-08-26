import path from "node:path";
import fs from "node:fs";
import { e2eLoginCredentials } from "./helpers/e2e-credentials";
import {
  buildSeedBody,
  formatG5Marker,
  isTerminalVersionConflict,
  isTransientSeedStatus as isTransientSeedHttpStatus,
  selectPortfolioVersion,
} from "./helpers/portfolio-seed-version";

/**
 * Playwright Global Setup — health-check poller + Golden-State seeder.
 *
 * Portfolio seeding (B1 Wave 5b): login → authenticated GET /api/portfolio once →
 * POST /api/internal/portfolio/seed with frozen expectedVersion. Transient
 * transport/cold-start retries reuse that frozen body; 409 is terminal.
 */

const GATEWAY_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const DEEP_HEALTH_URL = `${GATEWAY_BASE}/api/portfolio/health`;
const SHALLOW_HEALTH_URL = `${GATEWAY_BASE}/actuator/health`;

let INTERNAL_API_KEY =
  process.env.INTERNAL_API_KEY || process.env.TF_VAR_internal_api_key;

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

const SEEDED_DEMO_USER_ID = "00000000-0000-0000-0000-000000000e2e";
const TEST_USER_ID = process.env.E2E_TEST_USER_ID ?? SEEDED_DEMO_USER_ID;

const POLL_INTERVAL_MS = 2_000;
const DEEP_CHECK_TIMEOUT_MS = 30_000;
const TOTAL_TIMEOUT_MS = Number(process.env.HEALTH_CHECK_TIMEOUT_MS ?? 120_000);
const SKIP_BACKEND_HEALTH_CHECK =
  (process.env.SKIP_BACKEND_HEALTH_CHECK ?? "").toLowerCase() === "true";
const SKIP_GOLDEN_STATE_SEEDING =
  (process.env.SKIP_GOLDEN_STATE_SEEDING ?? "").toLowerCase() === "true";
const SKIP_MARKET_DATA_SEED =
  (process.env.SKIP_MARKET_DATA_SEED ?? "").toLowerCase() === "true";
const IS_LOCAL_GATEWAY = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(
  GATEWAY_BASE,
);
const SEED_MAX_RETRIES = Number(
  process.env.SEED_MAX_RETRIES ?? (IS_LOCAL_GATEWAY ? 3 : 8),
);
const SEED_RETRY_DELAY_MS = Number(
  process.env.SEED_RETRY_DELAY_MS ?? (IS_LOCAL_GATEWAY ? 5_000 : 10_000),
);
const SEED_REQUEST_TIMEOUT_MS = Number(
  process.env.SEED_REQUEST_TIMEOUT_MS ?? 70_000,
);
const SEED_WARMUP_TIMEOUT_MS = Number(
  process.env.SEED_WARMUP_TIMEOUT_MS ?? (IS_LOCAL_GATEWAY ? 10_000 : 60_000),
);
const SEED_WARMUP_PATHS = [
  "/api/portfolio/health",
  "/api/market/health",
  "/api/insights/health",
];

function timestamp(): string {
  return new Date().toISOString();
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isTransientSeedStatus(status: number, body: string): boolean {
  if (isTerminalVersionConflict(status)) {
    return false;
  }
  if (status === 503 && body.includes("internal_api_key_not_configured")) {
    return false;
  }
  return isTransientSeedHttpStatus(status);
}

async function safeResponseText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch (error) {
    return `<failed to read response body: ${error instanceof Error ? error.message : String(error)}>`;
  }
}

function bodyExcerpt(body: string, maxLength = 500): string {
  if (!body) {
    return "<empty response body>";
  }
  return body.length <= maxLength ? body : `${body.slice(0, maxLength)}…`;
}

export type SeedPortfolioWithFrozenVersionDeps = {
  apiBase: string;
  internalApiKey: string;
  email: string;
  password: string;
  expectedUserId: string;
  maxAttempts?: number;
  sleepFn?: (ms: number) => Promise<void>;
  fetchFn?: typeof fetch;
};

/**
 * Login → GET /api/portfolio once → POST seed with frozen expectedVersion.
 * Transport retries reuse the same body; 409 fails on attempt 1.
 */
export async function seedPortfolioWithFrozenVersion(
  deps: SeedPortfolioWithFrozenVersionDeps,
): Promise<{ expectedVersion: number; portfolioId?: string }> {
  const fetchFn = deps.fetchFn ?? fetch;
  const sleepFn = deps.sleepFn ?? sleep;
  const maxAttempts = deps.maxAttempts ?? SEED_MAX_RETRIES;
  const apiBase = deps.apiBase.replace(/\/+$/, "");

  // No AbortSignal.timeout: Vitest/MSW's patched fetch rejects cross-realm signals.
  const loginRes = await fetchFn(`${apiBase}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: deps.email, password: deps.password }),
  });
  if (!loginRes.ok) {
    throw new Error(
      `Portfolio seed login failed: HTTP ${loginRes.status} (credentials/response omitted)`,
    );
  }
  const loginJson = (await loginRes.json()) as {
    token?: string;
    userId?: string;
  };
  if (!loginJson.token) {
    throw new Error("Portfolio seed login returned no token");
  }
  if (loginJson.userId !== deps.expectedUserId) {
    throw new Error(
      `Portfolio seed login userId mismatch: expected ${deps.expectedUserId}, got ${String(loginJson.userId)}`,
    );
  }

  const portfolioRes = await fetchFn(`${apiBase}/api/portfolio`, {
    method: "GET",
    headers: { Authorization: `Bearer ${loginJson.token}` },
  });
  if (!portfolioRes.ok) {
    throw new Error(
      `Portfolio seed version read failed: HTTP ${portfolioRes.status} (body omitted)`,
    );
  }
  const portfolioJson: unknown = await portfolioRes.json();
  const expectedVersion = selectPortfolioVersion(
    portfolioJson,
    deps.expectedUserId,
  );
  const seedBodyJson = JSON.stringify(buildSeedBody(expectedVersion));

  console.log(formatG5Marker("global-setup", expectedVersion));

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const seedRes = await fetchFn(`${apiBase}/api/internal/portfolio/seed`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Internal-Api-Key": deps.internalApiKey,
        },
        body: seedBodyJson,
      });
      const responseBody = seedRes.ok
        ? ""
        : await safeResponseText(seedRes.clone());

      if (isTerminalVersionConflict(seedRes.status)) {
        throw new Error(
          `Portfolio seeding failed on attempt ${attempt}: HTTP 409 ` +
            bodyExcerpt(responseBody),
        );
      }

      const transient = isTransientSeedStatus(seedRes.status, responseBody);
      if (transient && attempt < maxAttempts) {
        console.log(
          `[${timestamp()}] Portfolio seeding: HTTP ${seedRes.status} on attempt ${attempt}/${maxAttempts} ` +
            `(transient transport/cold-start; frozen expectedVersion=${expectedVersion}) — ` +
            `retrying in ${SEED_RETRY_DELAY_MS}ms...`,
        );
        await sleepFn(SEED_RETRY_DELAY_MS);
        continue;
      }

      if (!seedRes.ok) {
        throw new Error(
          `Portfolio seeding failed after ${attempt}/${maxAttempts} attempts: ` +
            `HTTP ${seedRes.status}` +
            `${transient ? " (transient status, retry budget exhausted)" : ""} ` +
            bodyExcerpt(responseBody),
        );
      }

      let portfolioId: string | undefined;
      try {
        const seeded = (await seedRes.json()) as { portfolioId?: string };
        portfolioId = seeded.portfolioId;
      } catch {
        portfolioId = undefined;
      }
      return { expectedVersion, portfolioId };
    } catch (error) {
      if (error instanceof Error && /HTTP 409/.test(error.message)) {
        throw error;
      }
      if (attempt >= maxAttempts) {
        throw new Error(
          `Portfolio seeding: request failed after ${attempt}/${maxAttempts} attempts: ` +
            `${error instanceof Error ? error.message : String(error)}`,
        );
      }
      console.log(
        `[${timestamp()}] Portfolio seeding: request failed on attempt ${attempt}/${maxAttempts}: ` +
          `${error instanceof Error ? error.message : String(error)} — retrying in ${SEED_RETRY_DELAY_MS}ms...`,
      );
      await sleepFn(SEED_RETRY_DELAY_MS);
    }
  }
  throw new Error(`Portfolio seeding: exhausted ${maxAttempts} retries`);
}

async function warmSeedDependencies(): Promise<void> {
  console.log(
    `[${timestamp()}] Warming backend seed dependencies via gateway health endpoints...`,
  );
  for (const warmupPath of SEED_WARMUP_PATHS) {
    const ok = await poll(`${GATEWAY_BASE}${warmupPath}`, SEED_WARMUP_TIMEOUT_MS);
    if (!ok) {
      console.warn(
        `[${timestamp()}] Warm-up warning: ${warmupPath} did not return 200 within ${SEED_WARMUP_TIMEOUT_MS}ms. ` +
          `Continuing because seed retries still handle Lambda cold starts.`,
      );
    }
  }
}

async function seedFetch(
  label: string,
  url: string,
  body: object,
  maxRetries = SEED_MAX_RETRIES,
): Promise<{
  response: Response;
  attempts: number;
  transient: boolean;
  body: string;
}> {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Internal-Api-Key": INTERNAL_API_KEY!,
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(SEED_REQUEST_TIMEOUT_MS),
      });

      const responseBody = res.ok ? "" : await safeResponseText(res.clone());
      const transient = isTransientSeedStatus(res.status, responseBody);
      if (transient && attempt < maxRetries) {
        console.log(
          `[${timestamp()}] ${label}: HTTP ${res.status} on attempt ${attempt}/${maxRetries} ` +
            `(transient Lambda/API Gateway response) — retrying in ${SEED_RETRY_DELAY_MS}ms...`,
        );
        await sleep(SEED_RETRY_DELAY_MS);
        continue;
      }

      return {
        response: res,
        attempts: attempt,
        transient,
        body: responseBody,
      };
    } catch (error) {
      if (attempt >= maxRetries) {
        throw new Error(
          `${label}: request failed after ${attempt}/${maxRetries} attempts for ${url}: ` +
            `${error instanceof Error ? error.message : String(error)}`,
        );
      }
      console.log(
        `[${timestamp()}] ${label}: request failed on attempt ${attempt}/${maxRetries}: ` +
          `${error instanceof Error ? error.message : String(error)} — retrying in ${SEED_RETRY_DELAY_MS}ms...`,
      );
      await sleep(SEED_RETRY_DELAY_MS);
    }
  }
  throw new Error(`${label}: exhausted ${maxRetries} retries`);
}

async function assertSeedOk(
  label: string,
  result: {
    response: Response;
    attempts: number;
    transient: boolean;
    body: string;
  },
): Promise<void> {
  if (result.response.ok) {
    return;
  }
  const responseBody = result.body || (await safeResponseText(result.response));
  throw new Error(
    `${label} failed after ${result.attempts}/${SEED_MAX_RETRIES} attempts: ` +
      `HTTP ${result.response.status}` +
      `${result.transient ? " (transient status, retry budget exhausted)" : ""} ` +
      bodyExcerpt(responseBody),
  );
}

async function runSeeding(): Promise<void> {
  if (SKIP_GOLDEN_STATE_SEEDING) {
    console.log(
      `[${timestamp()}] Skipping Golden State seeding: SKIP_GOLDEN_STATE_SEEDING=true.`,
    );
    return;
  }

  if (!INTERNAL_API_KEY) {
    console.warn(
      `[${timestamp()}] Skipping Golden State seeding: INTERNAL_API_KEY not set.`,
    );
    return;
  }

  console.log(
    `[${timestamp()}] Starting Golden State seeding for ${TEST_USER_ID}...`,
  );

  try {
    await warmSeedDependencies();

    // 1. Portfolio seeding — frozen expectedVersion (B1 Wave 5b).
    // Transport/cold-start retries reuse that body; 409 is terminal.
    const credentials = e2eLoginCredentials();
    const { portfolioId, expectedVersion } =
      await seedPortfolioWithFrozenVersion({
        apiBase: GATEWAY_BASE,
        internalApiKey: INTERNAL_API_KEY,
        email: credentials.email,
        password: credentials.password,
        expectedUserId: TEST_USER_ID,
      });
    console.log(
      `[${timestamp()}] Portfolio seeded. ID: ${portfolioId ?? "<none>"} (expectedVersion=${expectedVersion})`,
    );

    // 2. Market Data Seeding (skipped in prod/azure CI — ACA Job is the price source)
    if (!SKIP_MARKET_DATA_SEED) {
      const marketResult = await seedFetch(
        "Market data seeding",
        `${GATEWAY_BASE}/api/internal/market-data/seed`,
        { userId: TEST_USER_ID },
      );

      await assertSeedOk("Market data seeding", marketResult);
      console.log(`[${timestamp()}] Market data seeded.`);
    } else {
      console.log(
        `[${timestamp()}] Skipping market-data seed: SKIP_MARKET_DATA_SEED=true.`,
      );
    }

    // 3. Insight Seeding (Cache Eviction)
    const insightResult = await seedFetch(
      "Insight seeding",
      `${GATEWAY_BASE}/api/internal/insight/seed`,
      { userId: TEST_USER_ID, portfolioId },
    );

    await assertSeedOk("Insight seeding", insightResult);
    console.log(`[${timestamp()}] Insight cache evicted. Seeding complete.`);
  } catch (error) {
    console.error(`[${timestamp()}] Seeding ERROR:`, error);
    throw error;
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
  await runSeeding();

  if (SKIP_BACKEND_HEALTH_CHECK) {
    console.log(
      `[${timestamp()}] Gateway health poll skipped (SKIP_BACKEND_HEALTH_CHECK=true). ` +
        `Use this only for stack-less smoke; full E2E must wait for the gateway.`,
    );
    return;
  }

  console.log(
    `[${timestamp()}] Health-check starting (deep: ${DEEP_HEALTH_URL}, timeout: ${TOTAL_TIMEOUT_MS}ms)`,
  );

  const deepOk = await poll(
    DEEP_HEALTH_URL,
    Math.min(DEEP_CHECK_TIMEOUT_MS, TOTAL_TIMEOUT_MS),
  );

  if (deepOk) {
    console.log(`[${timestamp()}] Deep health-check passed`);
    return;
  }

  console.log(
    `[${timestamp()}] Deep health-check unavailable after ${DEEP_CHECK_TIMEOUT_MS}ms, falling back to shallow`,
  );

  const remainingMs = TOTAL_TIMEOUT_MS - DEEP_CHECK_TIMEOUT_MS;
  if (remainingMs <= 0) {
    throw new Error(
      `Health-check timed out after ${TOTAL_TIMEOUT_MS}ms. ` +
        `Deep endpoint ${DEEP_HEALTH_URL} never returned 200.`,
    );
  }

  const shallowOk = await poll(SHALLOW_HEALTH_URL, remainingMs);

  if (shallowOk) {
    console.log(`[${timestamp()}] Shallow health-check passed (fallback)`);
    return;
  }

  throw new Error(
    `Health-check timed out after ${TOTAL_TIMEOUT_MS}ms. ` +
      `Neither ${DEEP_HEALTH_URL} nor ${SHALLOW_HEALTH_URL} returned 200.`,
  );
}

export default globalSetup;

// eslint-disable-next-line @typescript-eslint/no-require-imports
if (typeof require !== "undefined" && require.main === module) {
  globalSetup().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
