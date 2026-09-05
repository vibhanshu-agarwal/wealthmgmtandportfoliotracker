/**
 * B2 Task 9.1 — real catalog integration.
 *
 * Proves the Asset Picker's Browse step against the actual authenticated
 * `GET /api/assets` contract through a real, cross-origin API Gateway — never a
 * mock. Task 1.11's client (`fetchCatalog`) and this task's gateway CORS fix
 * (`SecurityConfig`'s `If-None-Match`/`ETag` entries) are both exercised the same
 * way a real browser would: this spec makes no `page.route` calls of its own.
 *
 * Uses the ordinary Golden-State E2E identity (`helpers/browser-auth.ts` /
 * `helpers/api.ts`) — Task 9.6's demo-authenticated fixture exists for the
 * composition-write flows Task 9.2/9.8 own, and is not needed for this read-only
 * catalog path.
 *
 * LOCAL-ONLY — not wired into any CI workflow. Task 9.9 owns broader workflow
 * wiring; running this in CI without that task's parity checks would be an
 * unreviewed scope expansion. Requires, before invoking:
 *   - The Docker Compose stack up and healthy: `docker compose up -d`
 *     (see repository root `docker-compose.yml`)
 *   - Nothing else — this config builds with NEXT_PUBLIC_ENABLE_ASSET_PICKER=true
 *     itself and points NEXT_PUBLIC_API_BASE_URL at the gateway's real port.
 *
 * Run from `frontend/`:
 *   npx playwright test --config playwright.asset-catalog.real.config.ts
 */
import { expect, test } from "@playwright/test";
import type { Response } from "@playwright/test";
import { ensurePortfolioWithHoldings } from "./helpers/api";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";
import { activeTickers } from "./helpers/catalog";

function isRealAssetsGet(response: Response): boolean {
  return (
    response.request().method() === "GET" &&
    new URL(response.url()).pathname === "/api/assets"
  );
}

test.describe("Asset Picker — real catalog integration (Task 9.1)", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);
  });

  test("loads the real catalog with a cross-origin-exposed ETag, then revalidates with a genuine 304 and keeps the catalog", async ({
    page,
  }) => {
    // Playwright's `response.headers()` reads the raw wire response (CDP Network
    // domain) — it does NOT go through the page's own CORS-enforced Headers API,
    // so a truthy value here is only a sanity check that the server sent an ETag
    // at all, never proof the browser's own JavaScript could read it. That proof
    // is the 304 assertion below: `fetchCatalog`'s `response.headers.get("ETag")`
    // is the only thing that populates its module-level cache, so a real
    // If-None-Match reaching the server on the second request — and a genuine
    // 304 coming back — is only possible if the in-page fetch actually read a
    // real ETag value across the CORS boundary on the first one.
    const assetResponses: { status: number; etag: string | null }[] = [];
    page.on("response", (response) => {
      if (isRealAssetsGet(response)) {
        assetResponses.push({ status: response.status(), etag: response.headers()["etag"] ?? null });
      }
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await expect(dialog).toBeVisible();

    // The real catalog rendered — sampled from the canonical manifest, never a
    // hardcoded size or ticker list (Spec A Requirement 8.10).
    const [sampleTicker] = activeTickers();
    await expect(
      dialog.getByRole("checkbox", { name: `Select ${sampleTicker}` }),
    ).toBeVisible({ timeout: 15_000 });

    await expect.poll(() => assetResponses.length, { timeout: 15_000 }).toBeGreaterThan(0);
    expect(assetResponses[0].status).toBe(200);
    expect(assetResponses[0].etag, "sanity check: the server must send an ETag at all").toBeTruthy();

    await dialog.getByRole("button", { name: "Close" }).click();
    await expect(dialog).not.toBeVisible();

    // `useCatalog`'s 60s staleTime means a real reopen this soon would not
    // refetch at all. Fast-forward the page's own clock past it instead of a
    // real 61s sleep — installed only now, after the page already finished its
    // real login/render work, so nothing about the proof above depended on a
    // virtualized clock.
    await page.clock.install();
    await page.clock.fastForward("01:01");

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByRole("checkbox", { name: `Select ${sampleTicker}` }),
    ).toBeVisible({ timeout: 15_000 });

    await expect.poll(() => assetResponses.length, { timeout: 15_000 }).toBeGreaterThan(1);
    const revalidation = assetResponses[1];
    expect(
      revalidation.status,
      "an unchanged canonical catalog must revalidate with a genuine 304, not a second 200",
    ).toBe(304);
  });
});
