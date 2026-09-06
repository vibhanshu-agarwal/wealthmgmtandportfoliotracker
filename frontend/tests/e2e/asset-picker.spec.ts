/**
 * B2 Tasks 9.2 and 9.7 — real assembled-stack composition save proof.
 *
 * This is intentionally collected by `playwright.config.ts` in the flag-on CI
 * job. It makes no `page.route` calls: setup, picker saves, conflicts, and
 * cleanup all traverse the real gateway/backend. The preserved mocked Wave-1
 * coverage lives in `asset-picker.mocked.spec.ts` and is collected only by
 * `playwright.asset-picker.mocked.config.ts`.
 *
 * Run only against the coordinator's disposable assembled stack:
 *   NEXT_PUBLIC_ENABLE_ASSET_PICKER=true npx playwright test --config playwright.config.ts tests/e2e/asset-picker.spec.ts
 */
import { expect, test } from "@playwright/test";
import type { APIRequestContext, APIResponse, Page, Response } from "@playwright/test";
import { e2eLoginCredentials } from "./helpers/e2e-credentials";
import {
  assertExactPersistedHoldings,
  assertVersionAdvanced,
  chooseKnownDifferentHoldings,
  selectExactPortfolio,
  type CompositionHolding,
  type ObservedPortfolio,
} from "./helpers/asset-picker-real";
import { FIXED_E2E_USER_ID } from "./helpers/portfolio-seed-version";

const AUTH_STORAGE_KEY = "wmpt.auth.session";
const DEFAULT_GATEWAY_URL = "http://localhost:8080";
const PINNED_PICKER_QUANTITY = "31";
const CLEANUP_MAX_ATTEMPTS = 3;

type E2eSession = {
  token: string;
  userId: string;
  email: string;
  name: string;
};

function gatewayUrl(): string {
  return (process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_GATEWAY_URL).replace(/\/+$/, "");
}

function internalApiKey(): string {
  const key = process.env.INTERNAL_API_KEY ?? process.env.TF_VAR_internal_api_key;
  if (!key?.trim()) {
    throw new Error("[asset-picker-real] INTERNAL_API_KEY is required for unconditional E2E cleanup");
  }
  return key;
}

function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

async function authenticateE2eSession(request: APIRequestContext): Promise<E2eSession> {
  const response = await request.post(`${gatewayUrl()}/api/auth/login`, {
    data: e2eLoginCredentials(),
  });
  expect(response.status(), "ordinary E2E login must succeed before any composition write").toBe(200);
  const body = (await response.json()) as Partial<E2eSession>;
  if (
    typeof body.token !== "string" ||
    typeof body.userId !== "string" ||
    typeof body.email !== "string" ||
    typeof body.name !== "string"
  ) {
    throw new Error("[asset-picker-real] ordinary E2E login returned an incomplete session");
  }
  if (body.userId !== FIXED_E2E_USER_ID) {
    throw new Error(
      `[asset-picker-real] ordinary E2E login resolved ${body.userId}, not ${FIXED_E2E_USER_ID}`,
    );
  }
  return body as E2eSession;
}

async function observePortfolio(
  request: APIRequestContext,
  session: E2eSession,
): Promise<ObservedPortfolio> {
  const response = await request.get(`${gatewayUrl()}/api/portfolio`, { headers: bearer(session.token) });
  expect(response.status(), "identity-checked GET /api/portfolio must succeed").toBe(200);
  return selectExactPortfolio(await response.json(), FIXED_E2E_USER_ID);
}

async function putComposition(
  request: APIRequestContext,
  session: E2eSession,
  expectedVersion: number,
  holdings: readonly CompositionHolding[],
): Promise<APIResponse> {
  return request.put(`${gatewayUrl()}/api/portfolio/holdings`, {
    headers: { ...bearer(session.token), "Content-Type": "application/json" },
    data: { expectedVersion, holdings },
  });
}

async function writeKnownDifferentComposition(
  request: APIRequestContext,
  session: E2eSession,
  observed: ObservedPortfolio,
): Promise<ObservedPortfolio> {
  const holdings = chooseKnownDifferentHoldings(observed.holdings);
  const response = await putComposition(request, session, observed.version, holdings);
  expect(response.status(), "direct deterministic setup write must return 200").toBe(200);
  const persisted = selectExactPortfolio([await response.json()], FIXED_E2E_USER_ID);
  assertVersionAdvanced("direct deterministic setup", observed.version, persisted.version);
  assertExactPersistedHoldings(persisted.holdings, holdings);
  return persisted;
}

function withPinnedPickerEdit(holdings: readonly CompositionHolding[]): CompositionHolding[] {
  let changed = false;
  const edited = holdings.map((holding) => {
    if (holding.ticker !== "AAPL") return { ...holding };
    changed = true;
    return { ticker: "AAPL", quantity: PINNED_PICKER_QUANTITY };
  });
  if (!changed) {
    throw new Error("[asset-picker-real] deterministic setup must provide AAPL for the pinned picker edit");
  }
  return edited;
}

function isPortfolioGet(response: Response): boolean {
  return response.request().method() === "GET" && new URL(response.url()).pathname === "/api/portfolio";
}

function isCompositionPut(response: Response): boolean {
  return (
    response.request().method() === "PUT" &&
    new URL(response.url()).pathname === "/api/portfolio/holdings"
  );
}

function captureBrowserPortfolioReads(page: Page): {
  reads: ObservedPortfolio[];
  errors: Error[];
} {
  const reads: ObservedPortfolio[] = [];
  const errors: Error[] = [];
  page.on("response", (response) => {
    if (!isPortfolioGet(response)) return;
    void response
      .json()
      .then((payload) => reads.push(selectExactPortfolio(payload, FIXED_E2E_USER_ID)))
      .catch((error: unknown) =>
        errors.push(error instanceof Error ? error : new Error(String(error))),
      );
  });
  return { reads, errors };
}

function capturePickerWrites(page: Page): Array<{ status: number; body: unknown }> {
  const writes: Array<{ status: number; body: unknown }> = [];
  page.on("response", (response) => {
    if (!isCompositionPut(response)) return;
    void response
      .json()
      .then((body) => writes.push({ status: response.status(), body }))
      .catch(() => writes.push({ status: response.status(), body: undefined }));
  });
  return writes;
}

async function restoreGoldenState(
  request: APIRequestContext,
  session: E2eSession,
): Promise<void> {
  let observedConflict = false;
  for (let attempt = 1; attempt <= CLEANUP_MAX_ATTEMPTS; attempt += 1) {
    // Every attempt deliberately re-observes the identity-matched, current version.
    const observed = await observePortfolio(request, session);
    const response = await request.post(`${gatewayUrl()}/api/internal/portfolio/seed`, {
      headers: { "Content-Type": "application/json", "X-Internal-Api-Key": internalApiKey() },
      data: { expectedVersion: observed.version },
    });

    if (response.status() === 200) {
      if (observedConflict) {
        throw new Error(
          "[asset-picker-real] cleanup restored Golden State after an observed 409; the conflict still fails the test",
        );
      }
      return;
    }
    if (response.status() === 409) {
      observedConflict = true;
      continue;
    }
    throw new Error(
      `[asset-picker-real] version-bearing cleanup seed returned HTTP ${response.status()} on attempt ${attempt}`,
    );
  }
  throw new Error(
    `[asset-picker-real] version-bearing cleanup seed returned HTTP 409 on all ${CLEANUP_MAX_ATTEMPTS} attempts`,
  );
}

test.describe("Asset Picker — real composition save (Tasks 9.2, 9.7)", () => {
  let session: E2eSession | undefined;

  test.beforeEach(async ({ page, request }) => {
    session = await authenticateE2eSession(request);
    await page.addInitScript(
      (value: E2eSession) => window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(value)),
      session,
    );
  });

  test.afterEach(async ({ request }) => {
    // Unconditional hygiene: runs after both a passing and a failing case.
    await restoreGoldenState(request, session ?? (await authenticateE2eSession(request)));
  });

  test("picker save sends one real PUT, strictly advances its loaded version, and persists the full edited draft", async ({
    page,
    request,
  }) => {
    const beforeSetup = await observePortfolio(request, session!);
    const setup = await writeKnownDifferentComposition(request, session!, beforeSetup);
    const browserReads = captureBrowserPortfolioReads(page);
    const pickerWrites = capturePickerWrites(page);

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Edit Holdings" })).toBeVisible();
    await expect.poll(() => browserReads.reads.length, { timeout: 15_000 }).toBeGreaterThan(0);
    if (browserReads.errors.length > 0) throw browserReads.errors[0]!;
    const pickerOpened = browserReads.reads.at(-1)!;
    expect(pickerOpened.version).toBe(setup.version);
    assertExactPersistedHoldings(pickerOpened.holdings, setup.holdings);

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    const aaplQuantity = dialog.getByRole("textbox", { name: "AAPL quantity" });
    await expect(aaplQuantity).toHaveValue(
      pickerOpened.holdings.find((holding) => holding.ticker === "AAPL")!.quantity,
    );
    await aaplQuantity.fill(PINNED_PICKER_QUANTITY);
    const expectedDraft = withPinnedPickerEdit(pickerOpened.holdings);

    await dialog.getByRole("button", { name: "Review changes" }).click();
    await dialog.getByRole("button", { name: "Save changes" }).click();

    await expect.poll(() => pickerWrites.length, { timeout: 15_000 }).toBe(1);
    const save = pickerWrites[0]!;
    expect(save.status, "the one picker save must receive real HTTP 200").toBe(200);
    const saveResponse = selectExactPortfolio([save.body], FIXED_E2E_USER_ID);
    assertVersionAdvanced("picker save", pickerOpened.version, saveResponse.version);

    const persistedAfterSave = await observePortfolio(request, session!);
    assertExactPersistedHoldings(persistedAfterSave.holdings, expectedDraft);
  });

  test("stale picker save receives one real 409 and freezes the visible draft without retrying", async ({
    page,
    request,
  }) => {
    const beforeSetup = await observePortfolio(request, session!);
    await writeKnownDifferentComposition(request, session!, beforeSetup);
    const browserReads = captureBrowserPortfolioReads(page);
    const pickerWrites = capturePickerWrites(page);

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Edit Holdings" })).toBeVisible();
    await expect.poll(() => browserReads.reads.length, { timeout: 15_000 }).toBeGreaterThan(0);
    if (browserReads.errors.length > 0) throw browserReads.errors[0]!;
    const pickerOpened = browserReads.reads.at(-1)!;

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await dialog.getByRole("textbox", { name: "AAPL quantity" }).fill(PINNED_PICKER_QUANTITY);

    const independentWrite = await writeKnownDifferentComposition(request, session!, pickerOpened);
    assertVersionAdvanced("independent stale-version writer", pickerOpened.version, independentWrite.version);

    await dialog.getByRole("button", { name: "Review changes" }).click();
    await dialog.getByRole("button", { name: "Save changes" }).click();
    await expect.poll(() => pickerWrites.length, { timeout: 15_000 }).toBe(1);
    expect(pickerWrites[0]!.status, "the stale picker save must receive real HTTP 409").toBe(409);

    await expect(dialog.getByText("Your portfolio changed elsewhere")).toBeVisible();
    const draftRegion = dialog.getByRole("region", { name: /your draft/i });
    await expect(draftRegion).toContainText("AAPL");
    await expect(draftRegion).toContainText(PINNED_PICKER_QUANTITY);
    await expect(dialog.getByRole("checkbox")).toHaveCount(0);
    await expect(dialog.getByRole("textbox")).toHaveCount(0);
    await expect(dialog.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    await expect(dialog.getByRole("button", { name: "Review changes" })).toHaveCount(0);

    await page.waitForTimeout(1_000);
    expect(pickerWrites).toHaveLength(1);
  });
});
