/**
 * B2 Task 9.5 — real portfolio asset-price freshness integration.
 *
 * Proves the Portfolio page consumes the authenticated real
 * `GET /api/portfolio/summary` `assetPriceFreshness` object and renders the
 * compact status + Details popover from that backend-owned object — never a
 * page.route fulfillment, rewrite, or browser-side freshness derivation.
 *
 * Deterministic STALE / UNKNOWN / MISSING / mixed-count / malformed / transport
 * failure paths stay in Vitest and portfolio-service contract tests. This spec
 * is successful-path local assembled-stack evidence only.
 *
 * Uses the ordinary Golden-State E2E identity (`helpers/browser-auth.ts`). No
 * Asset Picker feature flag; no composition PUT; no demo-only fixture.
 *
 * LOCAL-ONLY — ignored by the default `playwright.config.ts` Chromium project
 * (`testIgnore`); run only via `playwright.asset-freshness.real.config.ts`.
 * Task 9.9 owns broader Wave 9 CI.
 *
 * Fresh-stack setup (this config has no `globalSetup` of its own). From the
 * repository root, then from `frontend/`:
 *
 *   1. Bring the stack up with a real, non-blank INTERNAL_API_KEY:
 *        $env:INTERNAL_API_KEY = "<any non-empty local value>"
 *        docker compose up -d --build
 *      If host port 6379 is already bound, add a local untracked compose override
 *      remapping redis (`services.redis.ports: !override` → e.g. `6380:6379`) and
 *      pass it as an extra `-f`.
 *      Wait until healthy: `docker compose ps`.
 *   2. Seed Golden State + market data:
 *        cd frontend
 *        $env:INTERNAL_API_KEY = "<the same value as step 1>"
 *        $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
 *        npx ts-node --compiler-options '{"module":"commonjs"}' tests/e2e/global-setup.ts
 *   3. Run this spec, from `frontend/`:
 *        npx playwright test --config playwright.asset-freshness.real.config.ts
 *
 * Teardown (optional): `docker compose down` from the repository root. Do not
 * tear down unrelated containers.
 */
import { expect, test } from "@playwright/test";
import type { Response } from "@playwright/test";
import {
  buildFreshnessRows,
  describeFreshness,
  formatAbsoluteTimestamp,
} from "../../src/components/freshness/freshnessFormat";
import type { AssetPriceFreshnessDTO } from "../../types/portfolio";
import { ensurePortfolioWithHoldings } from "./helpers/api";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

const RECOGNIZED_STATES = new Set(["FRESH", "STALE", "UNKNOWN", "MISSING"]);

type CapturedSummary = {
  status: number;
  method: string;
  pathname: string;
  hasAuthorization: boolean;
  freshness: AssetPriceFreshnessDTO;
};

function normalizedPath(url: string): string {
  return new URL(url).pathname;
}

function isPortfolioSummaryGet(response: Response): boolean {
  if (response.request().method() !== "GET") return false;
  const pathname = normalizedPath(response.url());
  return pathname === "/api/portfolio/summary" || pathname.endsWith("/portfolio/summary");
}

function isNonNegativeInt(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}

/**
 * Runtime validation — a TypeScript cast is not evidence.
 */
function parseAssetPriceFreshness(body: unknown): AssetPriceFreshnessDTO {
  if (body === null || typeof body !== "object") {
    throw new Error("[asset-price-freshness] summary body is not an object");
  }
  const root = body as Record<string, unknown>;
  const raw = root.assetPriceFreshness;
  if (raw === null || typeof raw !== "object") {
    throw new Error("[asset-price-freshness] assetPriceFreshness object is absent");
  }
  const freshness = raw as Record<string, unknown>;
  const state = freshness.state;
  if (typeof state !== "string" || !RECOGNIZED_STATES.has(state)) {
    throw new Error(`[asset-price-freshness] unrecognized state: ${String(state)}`);
  }
  if (!isNonNegativeInt(freshness.staleHoldings)) {
    throw new Error("[asset-price-freshness] staleHoldings missing or invalid");
  }
  if (!isNonNegativeInt(freshness.unknownPriceHoldings)) {
    throw new Error("[asset-price-freshness] unknownPriceHoldings missing or invalid");
  }
  if (!isNonNegativeInt(freshness.missingPriceHoldings)) {
    throw new Error("[asset-price-freshness] missingPriceHoldings missing or invalid");
  }

  const timestamp = freshness.oldestKnownAssetPriceObservationTimestamp;
  if (timestamp !== undefined && typeof timestamp !== "string") {
    throw new Error(
      "[asset-price-freshness] oldestKnownAssetPriceObservationTimestamp must be string or omitted",
    );
  }

  const dto: AssetPriceFreshnessDTO = {
    state: state as AssetPriceFreshnessDTO["state"],
    staleHoldings: freshness.staleHoldings,
    unknownPriceHoldings: freshness.unknownPriceHoldings,
    missingPriceHoldings: freshness.missingPriceHoldings,
  };
  if (typeof timestamp === "string") {
    dto.oldestKnownAssetPriceObservationTimestamp = timestamp;
  }
  return dto;
}

test.describe("Portfolio — real asset-price freshness (Task 9.5)", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);
  });

  test("renders compact status and Details from the real summary assetPriceFreshness", async ({
    page,
  }) => {
    let captured: CapturedSummary | undefined;

    // Install the listener before navigation so the first real summary response
    // cannot be missed. Do not fulfill, abort, rewrite, or mock this route.
    page.on("response", async (response) => {
      if (!isPortfolioSummaryGet(response)) return;
      if (response.status() !== 200) return;
      if (captured) return;

      let body: unknown;
      try {
        body = await response.json();
      } catch {
        return;
      }

      const auth = response.request().headers()["authorization"] ?? "";
      captured = {
        status: response.status(),
        method: response.request().method(),
        pathname: normalizedPath(response.url()),
        hasAuthorization: /^Bearer\s+\S+/i.test(auth),
        freshness: parseAssetPriceFreshness(body),
      };
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
      timeout: 30_000,
    });

    await expect
      .poll(() => captured !== undefined, { timeout: 30_000 })
      .toBe(true);

    const summary = captured!;
    expect(summary.method).toBe("GET");
    expect(
      summary.pathname === "/api/portfolio/summary" ||
        summary.pathname.endsWith("/portfolio/summary"),
    ).toBe(true);
    expect(summary.hasAuthorization).toBe(true);
    expect(summary.status).toBe(200);

    const expected = describeFreshness(summary.freshness);
    const compact = page.locator("text=/Prices as of/i").first();

    // Await the compact status only after the captured response so a retained
    // DOM value from a prior navigation cannot satisfy the assertion.
    await expect(compact).toBeVisible({ timeout: 15_000 });
    await expect(compact).toContainText(expected.summary);
    await expect(compact).toContainText(expected.timestampLabel);

    await page.getByRole("button", { name: /details/i }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();

    const rows = buildFreshnessRows(summary.freshness);
    for (const row of rows) {
      if (row.count === null) {
        await expect(dialog.getByText(row.label, { exact: true })).toBeVisible();
      } else {
        await expect(dialog.getByText(`${row.label}: ${row.count}`, { exact: true })).toBeVisible();
      }
    }

    // Zero-count states must be absent (never "Stale: 0" etc.).
    if (summary.freshness.staleHoldings === 0) {
      await expect(dialog.getByText(/^Stale:/i)).toHaveCount(0);
    }
    if (summary.freshness.unknownPriceHoldings === 0) {
      await expect(dialog.getByText(/^Unknown:/i)).toHaveCount(0);
    }
    if (summary.freshness.missingPriceHoldings === 0) {
      await expect(dialog.getByText(/^Missing:/i)).toHaveCount(0);
    }

    const absolute = formatAbsoluteTimestamp(
      summary.freshness.oldestKnownAssetPriceObservationTimestamp,
    );
    await expect(dialog.getByText(absolute, { exact: true })).toBeVisible();
  });
});
