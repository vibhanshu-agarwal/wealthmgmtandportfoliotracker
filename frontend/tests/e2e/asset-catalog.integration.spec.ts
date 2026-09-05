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
 * unreviewed scope expansion.
 *
 * Fresh-stack setup (this config has no `globalSetup` of its own, unlike the
 * main `playwright.config.ts` — Golden State must be seeded explicitly). From
 * the repository root, then from `frontend/`:
 *
 *   1. Bring the stack up with a real, non-blank INTERNAL_API_KEY — the
 *      Golden-State seeder refuses to run against docker-compose.yml's default
 *      blank value (503 internal_api_key_not_configured):
 *        $env:INTERNAL_API_KEY = "<any non-empty local value>"
 *        docker compose up -d --build
 *      Wait for every service to report healthy: `docker compose ps`. If the
 *      host already has something else bound to the `redis` service's default
 *      host port 6379, add a local, untracked compose override remapping it
 *      (`services.redis.ports: !override` — a plain merge-append does NOT
 *      replace the conflicting entry) and pass it as an extra `-f` to both
 *      `build`/`up` — this is a host-specific workaround, never part of the repo.
 *   2. Seed Golden State — this dedicated config performs no seeding itself:
 *        cd frontend
 *        $env:INTERNAL_API_KEY = "<the same value as step 1>"
 *        $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
 *        npx ts-node --compiler-options '{"module":"commonjs"}' tests/e2e/global-setup.ts
 *   3. Run this spec, from `frontend/`:
 *        npx playwright test --config playwright.asset-catalog.real.config.ts
 *
 * See the Task 9.1 handoff note for the exact run log, including the
 * deliberate revert/rebuild/rerun that confirmed this spec actually fails
 * without the gateway CORS fix.
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
    // so a truthy `etag` here is only a sanity check that the server sent one at
    // all, never proof the browser's own JavaScript could read it. That proof is
    // the `sentIfNoneMatch`/`304` assertions below: `fetchCatalog`'s
    // `response.headers.get("ETag")` is the only thing that populates its
    // module-level cache, so the second request carrying the exact first ETag as
    // `If-None-Match` — and a genuine 304 coming back — is only possible if the
    // in-page fetch actually read a real ETag value across the CORS boundary on
    // the first one.
    const assetResponses: { status: number; etag: string | null; sentIfNoneMatch: string | null }[] = [];
    page.on("response", (response) => {
      if (isRealAssetsGet(response)) {
        assetResponses.push({
          status: response.status(),
          etag: response.headers()["etag"] ?? null,
          sentIfNoneMatch: response.request().headers()["if-none-match"] ?? null,
        });
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
    const initial = assetResponses[0];
    expect(initial.status).toBe(200);
    expect(initial.etag, "sanity check: the server must send an ETag at all").toBeTruthy();
    expect(
      initial.sentIfNoneMatch,
      "the very first request has no cached catalog yet and must not send If-None-Match",
    ).toBeNull();

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

    // Wait for the revalidation to actually complete — and assert on it —
    // BEFORE looking at the rendered Browse list. Checking the checkbox first
    // would only prove react-query's stale-while-revalidate cache still had the
    // first response's data; it says nothing about what happened once the
    // second request actually landed.
    await expect.poll(() => assetResponses.length, { timeout: 15_000 }).toBeGreaterThan(1);
    const revalidation = assetResponses[1];
    expect(
      revalidation.sentIfNoneMatch,
      "the second request must revalidate with the exact ETag the first response returned",
    ).toBe(initial.etag);
    expect(
      revalidation.status,
      "an unchanged canonical catalog must revalidate with a genuine 304, not a second 200",
    ).toBe(304);

    // Only now, after the 304 has genuinely been received and processed, confirm
    // fetchCatalog's cache-retention actually held: Browse is still populated
    // and no failure state appeared.
    await expect(
      dialog.getByRole("checkbox", { name: `Select ${sampleTicker}` }),
      "the catalog must remain rendered from the retained cache after a 304",
    ).toBeVisible({ timeout: 15_000 });
    await expect(
      dialog.getByRole("alert"),
      "a successful revalidation must not leave the catalog-failure state visible",
    ).toHaveCount(0);
  });
});
