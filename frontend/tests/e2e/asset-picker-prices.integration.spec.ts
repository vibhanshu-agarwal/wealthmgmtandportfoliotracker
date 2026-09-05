/**
 * B2 Task 9.3 — real drafted-asset price integration.
 *
 * Proves the Asset Picker's Browse step fetches real market prices only for the
 * current draft's tickers via authenticated `GET /api/market/prices?tickers=`
 * through the local API Gateway — never a page.route fulfillment for prices.
 *
 * Attribution: the browser-visible `GET /api/portfolio` response is narrowed to
 * AAPL + BTC-USD so the open-time draft starts there. The Portfolio page may also
 * request that same two-ticker set after enrichment — that alone is not picker
 * attribution. This spec then selects a third ACTIVE catalog ticker inside the
 * picker and proves the resulting real price request contains exactly
 * `AAPL,BTC-USD,<third>` (sorted) and drives that ticker's displayed estimate.
 * The Portfolio page's holdings remain the two-ticker set, so it cannot emit the
 * three-ticker query.
 *
 * Unavailable / failed-batch paths stay in Vitest+MSW contract tests
 * (`BrowseStep.test.tsx`, `portfolio.batching.test.ts`); this spec is successful-
 * price real-stack evidence only.
 *
 * Uses the ordinary Golden-State E2E identity (`helpers/browser-auth.ts`). No
 * demo-only fixture; no public composition PUT.
 *
 * LOCAL-ONLY — ignored by the default `playwright.config.ts` Chromium project
 * (`testIgnore`); run only via `playwright.draft-prices.real.config.ts`. Task 9.9
 * owns broader Wave 9 CI.
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
 *        npx playwright test --config playwright.draft-prices.real.config.ts
 *
 * Teardown (optional): `docker compose down` from the repository root. Do not
 * tear down unrelated containers.
 */
import { expect, test } from "@playwright/test";
import type { Page, Response } from "@playwright/test";
import { formatCurrency } from "../../src/lib/utils/format";
import { computeEstimatedValue } from "../../src/lib/utils/quantityDisplay";
import { ensurePortfolioWithHoldings } from "./helpers/api";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";
import { activeTickers } from "./helpers/catalog";

const HELD_TICKERS = ["AAPL", "BTC-USD"] as const;
const HELD_TICKER_SET = new Set<string>(HELD_TICKERS);
const DISTINCT_QUANTITY = "7";

/** Third ticker selected inside the picker — must not be in the narrowed holdings. */
function pickThirdTicker(): string {
  const third = activeTickers().find((ticker) => !HELD_TICKER_SET.has(ticker));
  if (!third) {
    throw new Error("[asset-picker-prices] no ACTIVE catalog ticker available beyond AAPL/BTC-USD");
  }
  return third;
}

type PriceRow = {
  ticker: string;
  currentPrice: number | null;
  priceUnavailable?: boolean;
  observedAt?: string | null;
};

type WirePortfolio = {
  id: string;
  userId?: string;
  version?: number;
  holdings?: Array<{ id?: string; assetTicker: string; quantity: string }>;
  [key: string]: unknown;
};

function isMarketPricesGet(response: Response): boolean {
  if (response.request().method() !== "GET") return false;
  const url = new URL(response.url());
  return url.pathname === "/api/market/prices" || url.pathname.endsWith("/market/prices");
}

function tickersFromPricesUrl(url: string): string[] {
  const param = new URL(url).searchParams.get("tickers");
  if (!param || param.trim() === "") return [];
  return param
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
}

function setsEqual(a: string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const sortedA = [...a].sort();
  const sortedB = [...b].sort();
  return sortedA.every((ticker, i) => ticker === sortedB[i]);
}

/**
 * Narrow the browser's portfolio read to AAPL + BTC-USD so open-time seeding is
 * a known two-ticker draft. Does not touch /api/market/prices.
 */
async function narrowBrowserPortfolioToHeldTargets(page: Page): Promise<void> {
  await page.route("**/api/portfolio", async (route) => {
    if (route.request().method() !== "GET") {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const body = (await response.json()) as WirePortfolio[];
    if (!Array.isArray(body)) {
      await route.fulfill({ response, body: JSON.stringify(body) });
      return;
    }
    const keep = new Set<string>(HELD_TICKERS);
    const narrowed = body.map((portfolio) => ({
      ...portfolio,
      holdings: (portfolio.holdings ?? []).filter((h) => keep.has(h.assetTicker)),
    }));
    await route.fulfill({
      status: response.status(),
      headers: {
        ...response.headers(),
        "content-type": "application/json",
      },
      body: JSON.stringify(narrowed),
    });
  });
}

test.describe("Asset Picker — real drafted price integration (Task 9.3)", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);
    await narrowBrowserPortfolioToHeldTargets(page);
  });

  test("fetches real prices for a picker-only third ticker and renders its estimate", async ({
    page,
  }) => {
    const thirdTicker = pickThirdTicker();
    const pickerAttributedTickers = [...HELD_TICKERS, thirdTicker];
    const pickerPriceBatches: { tickers: string[]; status: number; body: PriceRow[] | null }[] =
      [];
    let putCount = 0;

    page.on("request", (request) => {
      if (request.method() === "PUT" && request.url().includes("/api/portfolio/holdings")) {
        putCount += 1;
      }
    });

    page.on("response", async (response) => {
      if (!isMarketPricesGet(response)) return;
      const tickers = tickersFromPricesUrl(response.url());
      // Three-ticker set is picker-attributed: Portfolio holdings stay AAPL+BTC-USD only.
      if (!setsEqual(tickers, pickerAttributedTickers)) return;
      let body: PriceRow[] | null = null;
      if (response.ok()) {
        try {
          body = (await response.json()) as PriceRow[];
        } catch {
          body = null;
        }
      }
      pickerPriceBatches.push({ tickers, status: response.status(), body });
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await expect(dialog).toBeVisible({ timeout: 30_000 });

    await expect(
      dialog.getByRole("checkbox", { name: "Select AAPL", exact: true }),
    ).toBeVisible({
      timeout: 30_000,
    });
    await expect(
      dialog.getByRole("checkbox", { name: "Select BTC-USD", exact: true }),
    ).toBeVisible();

    // Controlled picker transition: add a third catalog ticker that is not in the
    // narrowed portfolio holdings — only the picker draft can request that set.
    const thirdCheckbox = dialog.getByRole("checkbox", {
      name: `Select ${thirdTicker}`,
      exact: true,
    });
    await expect(thirdCheckbox).toBeVisible({ timeout: 30_000 });
    await thirdCheckbox.click();
    await expect(thirdCheckbox).toHaveAttribute("aria-checked", "true");

    await expect
      .poll(() => pickerPriceBatches.some((b) => b.status === 200 && b.body != null), {
        timeout: 30_000,
        message:
          `after selecting ${thirdTicker} in the picker, a real GET /api/market/prices ` +
          `must request exactly AAPL,BTC-USD,${thirdTicker} (any order)`,
      })
      .toBe(true);

    const successful = pickerPriceBatches.find((b) => b.status === 200 && b.body != null);
    expect(successful, "expected at least one successful picker-attributed price batch").toBeTruthy();
    expect(setsEqual(successful!.tickers, pickerAttributedTickers)).toBe(true);

    const thirdRow = successful!.body!.find((row) => row.ticker === thirdTicker);
    expect(thirdRow, `${thirdTicker} must appear in the real price response`).toBeTruthy();
    expect(thirdRow!.priceUnavailable ?? false).toBe(false);
    expect(thirdRow!.currentPrice).not.toBeNull();
    expect(typeof thirdRow!.currentPrice).toBe("number");

    const quantityInput = dialog.getByRole("textbox", { name: `${thirdTicker} quantity` });
    await quantityInput.fill(DISTINCT_QUANTITY);

    const expectedEstimate = computeEstimatedValue(DISTINCT_QUANTITY, thirdRow!.currentPrice);
    expect(expectedEstimate, "sanity: quantity × real price must be computable").not.toBeNull();
    const expectedLabel = formatCurrency(expectedEstimate!);

    const thirdDraftRow = dialog.locator(`[data-ticker="${thirdTicker}"]`);
    await expect(
      thirdDraftRow.getByText(expectedLabel, { exact: true }),
      `Browse must render ${thirdTicker}'s estimate on that row from the captured real three-ticker price response`,
    ).toBeVisible({ timeout: 15_000 });

    await expect(quantityInput).toHaveValue(DISTINCT_QUANTITY);
    expect(putCount).toBe(0);
  });

  test("empty draft after deselecting all targets dispatches no picker price request for that empty set", async ({
    page,
  }) => {
    const emptyDraftRequests: string[] = [];

    page.on("request", (request) => {
      if (request.method() !== "GET") return;
      if (!request.url().includes("/market/prices")) return;
      const tickers = tickersFromPricesUrl(request.url());
      if (tickers.length === 0) {
        emptyDraftRequests.push(request.url());
      }
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await expect(dialog).toBeVisible({ timeout: 30_000 });
    await expect(
      dialog.getByRole("checkbox", { name: "Select AAPL", exact: true }),
    ).toBeVisible({
      timeout: 30_000,
    });

    for (const ticker of HELD_TICKERS) {
      await dialog.getByRole("checkbox", { name: `Select ${ticker}`, exact: true }).click();
      await expect(
        dialog.getByRole("checkbox", { name: `Select ${ticker}`, exact: true }),
      ).toHaveAttribute("aria-checked", "false");
    }

    // Empty draft: useDraftPrices sets enabled:false — must not call the unfiltered
    // prices endpoint (blank tickers would return the server's entire price set).
    const emptyRequestsBefore = emptyDraftRequests.length;
    const quietUntil = Date.now() + 2_000;
    await expect
      .poll(() => Date.now() >= quietUntil, { timeout: 3_000, intervals: [250] })
      .toBe(true);
    expect(emptyDraftRequests.length).toBe(emptyRequestsBefore);
  });
});
