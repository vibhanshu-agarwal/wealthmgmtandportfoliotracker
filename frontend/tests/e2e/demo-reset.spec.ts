import type { Page, Request } from "@playwright/test";
import { test, expect, DEMO_USER_ID } from "./helpers/demo-auth";
import {
  assertExactHoldings,
  loadDemoGoldenOracle,
  readDemoPortfolio,
  restoreDemoGoldenState,
  selectDemoPortfolio,
  writeNonGoldenDemoComposition,
  type DemoPortfolio,
  type ExactHolding,
} from "./helpers/demo-reset";

const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const portfolioUrl = `${apiBaseUrl}/api/portfolio`;
const resetUrl = `${portfolioUrl}/demo-reset`;
const internalApiKey = process.env.INTERNAL_API_KEY ?? "";
let golden: ExactHolding[];

// Task 9.8 runs with the real Docker-assembled backend and both build flags on.
// The demo fixture owns a private browser context; neither case borrows the
// ordinary E2E user's state. Sensitive authentication/internal calls are uncaptured.
test.use({ trace: "off", screenshot: "off", video: "off" });
test.describe.configure({ retries: 0 });

test.beforeAll(() => {
  expect(internalApiKey.trim().length, "INTERNAL_API_KEY is required before any demo mutation").toBeGreaterThan(0);
  golden = loadDemoGoldenOracle();
});

test.afterEach(async ({ request, demoSession, demoPage }) => {
  // Unconditional and independent: stop browser work first, then always freshly
  // observe the demo subject and restore via internal POST, even if public reset
  // never reached its endpoint. The fixed E2E seed route cannot target this user.
  try {
    await demoPage.close();
  } finally {
    await restoreDemoGoldenState({ request, apiBaseUrl, token: demoSession.token }, golden, internalApiKey);
  }
});

async function openObservedDemo(page: Page): Promise<DemoPortfolio> {
  // Install before loading, and pause only after the UI has genuinely observed
  // its portfolio. This freezes periodic refresh during the deliberately stale
  // write; clock.runFor below then exercises timers for the no-retry assertion.
  await page.clock.install();
  const loaded = page.waitForResponse((response) => response.url() === portfolioUrl && response.request().method() === "GET");
  await page.goto("/portfolio");
  const response = await loaded;
  expect(response.status()).toBe(200);
  const observed = selectDemoPortfolio(await response.json());
  await expect(page.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toBeEnabled();
  await page.clock.pauseAt(await page.evaluate(() => Date.now() + 1000));
  return observed;
}

async function expectVisibleHoldings(page: Page, holdings: ExactHolding[]) {
  // Whole-table comparison: no missing/extra ticker can hide behind a convenient
  // AAPL/BTC subset. Golden and deliberate setup quantities are small integers;
  // derive their display strings without the production formatter or Number().
  const expected = holdings.map((h) => ({ assetTicker: h.assetTicker, quantity: h.quantity.replace(/\.0{8}$/, ".00") }))
    .sort((a, b) => a.assetTicker.localeCompare(b.assetTicker));
  await expect.poll(async () => page.getByRole("table").locator("tbody tr").evaluateAll((rows) => rows.map((row) => ({
    assetTicker: row.querySelector("td .font-mono")?.textContent?.trim() ?? "",
    quantity: row.querySelectorAll("td")[1]?.textContent?.trim() ?? "",
  })).sort((a, b) => a.assetTicker.localeCompare(b.assetTicker))), { timeout: 20_000 }).toEqual(expected);
}

test("demo reset restores the exact golden state through the real public gateway route", async ({ demoPage, demoSession, request }) => {
  const api = { request, apiBaseUrl, token: demoSession.token };
  const setup = await writeNonGoldenDemoComposition(api, golden);
  const observed = await openObservedDemo(demoPage);
  expect(observed.version).toBe(setup.version);
  assertExactHoldings(observed.holdings, setup.holdings);
  await expectVisibleHoldings(demoPage, setup.holdings);

  const resets: Request[] = [];
  demoPage.on("request", (req) => { if (req.url() === resetUrl && req.method() === "PUT") resets.push(req); });
  const resetResponse = demoPage.waitForResponse((response) => response.url() === resetUrl && response.request().method() === "PUT");
  await demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true }).click();
  const response = await resetResponse;
  expect(response.status()).toBe(200);
  expect(resets).toHaveLength(1);
  expect(resets[0].postDataJSON()).toEqual({ expectedVersion: observed.version });
  expect(resets[0].headers().authorization === `Bearer ${demoSession.token}`).toBe(true);
  expect(resets[0].headers()["x-internal-api-key"]).toBeUndefined();

  const reset = selectDemoPortfolio([await response.json()]);
  expect(reset.userId).toBe(DEMO_USER_ID);
  expect(reset.id).toBe(observed.id);
  expect(reset.version).toBe(observed.version + 1);
  assertExactHoldings(reset.holdings, golden);
  const persisted = await readDemoPortfolio(api);
  expect(persisted.id).toBe(reset.id);
  expect(persisted.version).toBe(reset.version);
  assertExactHoldings(persisted.holdings, golden);
  await expect(demoPage.getByRole("status").filter({ hasText: "Demo portfolio reset." })).toBeVisible();
  await expectVisibleHoldings(demoPage, golden);
  await expect(demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toBeEnabled();
  await demoPage.clock.runFor(31_000);
  expect(resets).toHaveLength(1);
});

test("stale demo reset returns one genuine 409 and stays frozen until explicit re-observation", async ({ demoPage, demoSession, request }) => {
  const api = { request, apiBaseUrl, token: demoSession.token };
  const setup = await writeNonGoldenDemoComposition(api, golden);
  const observed = await openObservedDemo(demoPage);
  expect(observed.version).toBe(setup.version);
  assertExactHoldings(observed.holdings, setup.holdings);
  await expectVisibleHoldings(demoPage, setup.holdings);

  const concurrent = await writeNonGoldenDemoComposition(api, golden);
  expect(concurrent.version).toBeGreaterThan(observed.version);
  const resets: Request[] = [];
  demoPage.on("request", (req) => { if (req.url() === resetUrl && req.method() === "PUT") resets.push(req); });
  const resetResponse = demoPage.waitForResponse((response) => response.url() === resetUrl && response.request().method() === "PUT");
  await demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true }).click();
  const response = await resetResponse;
  expect(response.status()).toBe(409);
  expect(resets).toHaveLength(1);
  expect(resets[0].postDataJSON()).toEqual({ expectedVersion: observed.version });
  const conflict = await response.json();
  expect(conflict.error).toBe("portfolio_version_conflict");
  expect(conflict.currentVersion).toBe(concurrent.version);
  expect(typeof conflict.message).toBe("string");
  expect(conflict.message.length).toBeGreaterThan(0);
  await expect(demoPage.getByRole("alert")).toContainText("Your portfolio changed elsewhere.");
  await expect(demoPage.getByRole("alert")).toContainText(conflict.message);
  await expect(demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toHaveCount(0);
  await expect(demoPage.getByRole("button", { name: "Refresh & try again", exact: true })).toBeEnabled();
  const persisted = await readDemoPortfolio(api);
  expect(persisted.version).toBe(concurrent.version);
  assertExactHoldings(persisted.holdings, concurrent.holdings);

  // Cover retry delays and a periodic portfolio refresh. Even newer props must
  // not clear the conflict or resubmit without the user's explicit observation.
  const backgroundResponse = demoPage.waitForResponse((res) => res.url() === portfolioUrl && res.request().method() === "GET");
  await demoPage.clock.runFor(65_000);
  const background = await backgroundResponse;
  expect(background.status()).toBe(200);
  expect(selectDemoPortfolio(await background.json()).version).toBe(concurrent.version);
  await expectVisibleHoldings(demoPage, concurrent.holdings);
  expect(resets).toHaveLength(1);
  await expect(demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toHaveCount(0);
  await expect(demoPage.getByRole("alert")).toContainText("Your portfolio changed elsewhere.");
  const refreshedResponse = demoPage.waitForResponse((res) => res.url() === portfolioUrl && res.request().method() === "GET");
  await demoPage.getByRole("button", { name: "Refresh & try again", exact: true }).click();
  const refreshed = await refreshedResponse;
  expect(refreshed.status()).toBe(200);
  expect(selectDemoPortfolio(await refreshed.json()).version).toBe(concurrent.version);
  await expect(demoPage.getByRole("button", { name: "Reset Demo Portfolio", exact: true })).toBeEnabled();
  await expect(demoPage.getByRole("alert")).toHaveCount(0);
  await expectVisibleHoldings(demoPage, concurrent.holdings);
  expect(resets).toHaveLength(1);
});
