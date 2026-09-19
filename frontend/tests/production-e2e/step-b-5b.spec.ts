/**
 * Wave 10.2 Step B, exit criterion 5b - production browser legs L0-L9.
 *
 * AUTHORING ONLY. This file is never run by an agent; the owner runs it through
 * scripts/verify_step_b_5b.py against the live site. See
 * docs/superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md
 * (section 4.1 and Appendices A and B) for the contract.
 *
 * The spec observes and appends to <STEP_B_5B_WORK_DIR>/ledger.jsonl. It has NO
 * verdict authority: the orchestrator derives every result from the ledger. It
 * drives the page and asserts only on the page's own network events (armed before
 * each triggering action, no route interception), never on page text beyond a few
 * fixed strings, and never writes error messages, page text, tickers, bodies,
 * headers or tokens into the ledger; only closed-vocabulary codes, booleans and
 * counts (see lib/ledger.ts and ledger-contract.json).
 *
 * Nothing is read from the environment at import time. The whole configuration is
 * read once, inside the test body, through lib/environment.ts; the test refuses to
 * start unless both origins are exactly the two allowlisted production origins.
 * No mutating action is ever retried.
 */
import { readFileSync, statSync } from "node:fs";
import path from "node:path";
import { test } from "@playwright/test";
import type { BrowserContext, Locator, Page } from "@playwright/test";
import { loadLedgerContract } from "./lib/contract";
import type { LedgerContract } from "./lib/contract";
import { readStepB5bEnvironment } from "./lib/environment";
import type { StepB5bConfig } from "./lib/environment";
import { independentPortfolioRead } from "./lib/independent-read";
import type { FetchLike } from "./lib/independent-read";
import { createFsLedgerSink, failureReasonForStatus, LEDGER_FILE_NAME, LedgerWriter } from "./lib/ledger";
import { decideMutationGate, LegCheck, LegRunner } from "./lib/leg-runner";
import type { LegReport } from "./lib/leg-runner";
import { httpOf, ledgerStatusOf, PageRecorder, waitForQuietPageReads } from "./lib/page-recorder";
import type { RecordedRequest, RequestLike } from "./lib/page-recorder";
import {
  chooseAttributableTicker,
  evaluatePriceObservations,
  priceHttpEntries,
  priceLegFailure,
} from "./lib/price-attribution";
import { urlIsOnOriginAtPath } from "./lib/requests";
import {
  buildExpectedDraft,
  chooseEditTicker,
  holdingPairsEqual,
  parseGoldenFile,
  parseRequestJson,
  putBodyMatchesDraft,
  resetBodyMatches,
  sameTickerSet,
} from "./lib/response-shapes";
import type { GoldenState } from "./lib/response-shapes";
import { isRecord } from "./lib/guards";
import { pollUntil, realClock, waitHidden, waitVisible } from "./lib/wait";
import type { Clock } from "./lib/wait";

/** The localStorage key src/lib/auth/session.ts reads the session from. */
const AUTH_STORAGE_KEY = "wmpt.auth.session";
/** Shown nowhere important; the session only needs a non-empty display name. */
const SESSION_DISPLAY_NAME = "Demo User";

const POLL_INTERVAL_MS = 250;
/** Long enough to catch a stray second request (a retry) after the first response. */
const QUIET_WINDOW_MS = 3_000;
const UNAVAILABLE_NOTICE = "Editing is temporarily unavailable";

interface SharedState {
  /** Recorder mark taken immediately before the L3 click; L4/L5 count requests after it. */
  openMark: number;
  anotherSessionActive: boolean | null;
  /** The version returned by the L8 save, consumed by L9. */
  savedVersion: number | null;
}

interface LegContext {
  readonly page: Page;
  readonly recorder: PageRecorder;
  readonly config: StepB5bConfig;
  readonly golden: GoldenState;
  readonly clock: Clock;
  readonly fetchImpl: FetchLike;
  readonly shared: SharedState;
  /** Writes the write-ahead `armed` ledger event; called once, immediately before Save. */
  readonly arm: () => void;
}

// -- locators ------------------------------------------------------------------

const portfolioHeading = (page: Page): Locator =>
  page.getByRole("heading", { level: 1, name: "Portfolio", exact: true });
const editHoldingsButton = (page: Page): Locator =>
  page.getByRole("button", { name: "Edit Holdings", exact: true });
const resetButton = (page: Page): Locator =>
  page.getByRole("button", { name: "Reset Demo Portfolio", exact: true });
const pickerDialog = (page: Page): Locator => page.getByRole("dialog", { name: "Edit Holdings" });
const statusMessage = (page: Page, text: string): Locator =>
  page.getByRole("status").filter({ hasText: text }).first();

// -- small helpers -------------------------------------------------------------

function pollOptions(ctx: LegContext) {
  return { timeoutMs: ctx.config.actionTimeoutMs, intervalMs: POLL_INTERVAL_MS, clock: ctx.clock };
}

/** Round-trips to the page so every network event emitted before this point has been delivered. */
async function flushPageEvents(page: Page): Promise<void> {
  await page.evaluate(() => undefined);
}

/**
 * Pacing before a write (spec 4.1): wait until the page's own portfolio and price reads have all settled and
 * stayed quiet for about 2 s, so its 60 s refetch cannot land in the same rate-limit second as the write. Bounded
 * by the action timeout on the injected clock; false means the page never went quiet, and the caller must stop
 * BEFORE sending anything.
 */
function waitForQuietPage(ctx: LegContext): Promise<boolean> {
  return waitForQuietPageReads(ctx.recorder, pollOptions(ctx));
}

async function headerAbsent(request: RequestLike, lowerCaseName: string): Promise<boolean> {
  try {
    const headers = await request.allHeaders();
    return !Object.keys(headers).some((name) => name.toLowerCase() === lowerCaseName);
  } catch {
    // Absence cannot be proven: fail closed.
    return false;
  }
}

function isDirectory(candidate: string): boolean {
  try {
    return statSync(candidate).isDirectory();
  } catch {
    return false;
  }
}

/** Runs inside the page before any of its scripts; installs the demo session for the frontend origin only. */
const installSession = (arg: { key: string; value: string; origin: string }): void => {
  try {
    if (window.location.origin !== arg.origin) return;
    window.localStorage.setItem(arg.key, arg.value);
  } catch {
    // Storage is unavailable in this frame (for example an opaque origin): nothing to install.
  }
};

function attachRecorder(page: Page, recorder: PageRecorder): void {
  page.on("request", (request) => recorder.onRequest(request));
  page.on("response", (response) => {
    void recorder.onResponse(response);
  });
  page.on("requestfailed", (request) => recorder.onRequestFailed(request));
  page.on("console", (message) => recorder.onConsole(message));
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) recorder.onMainFrameNavigated(frame.url());
  });
}

/** Records L0 failed and every later leg skipped when the run cannot even start. */
function recordRefusal(contract: LedgerContract, workDir: string | null): void {
  if (workDir === null || !isDirectory(workDir)) return;
  try {
    const writer = new LedgerWriter({ contract, sink: createFsLedgerSink(path.join(workDir, LEDGER_FILE_NAME)) });
    const runner = new LegRunner({ writer });
    runner.fail("L0", "ASSERTION_FAILED");
    runner.finish();
  } catch {
    // The refusal below still fails the test; the orchestrator treats missing legs as failures.
  }
}

// -- legs ----------------------------------------------------------------------

/** L0: authenticated by the injected session, /portfolio opens, no redirect to /login, no 401. */
async function runL0(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config } = ctx;
  await page.goto(`${config.frontendOrigin}/portfolio`, {
    waitUntil: "domcontentloaded",
    timeout: config.actionTimeoutMs,
  });
  const headingPortfolio = await waitVisible(portfolioHeading(page), config.actionTimeoutMs);
  // The h1 ships in the static HTML, so it is visible before hydration and before a client-side
  // redirect to /login could happen. Wait until the app has demonstrably started (its first
  // authenticated portfolio request, or a login redirect / 401) before judging the final URL.
  await pollUntil(
    () =>
      recorder.find("GET", "/api/portfolio").length > 0 || recorder.visitedLogin() || recorder.unauthorized401Seen(),
    pollOptions(ctx),
  );
  // Origin AND path: another origin serving /portfolio must not count as having stayed on the site.
  const finalPathIsPortfolio = urlIsOnOriginAtPath(page.url(), config.frontendOrigin, "/portfolio");
  const redirectedToLogin = recorder.visitedLogin();
  const unauthorized401Seen = recorder.unauthorized401Seen();

  const check = new LegCheck();
  check.failIf(unauthorized401Seen, "HTTP_STATUS_NOT_200");
  check.failIf(!finalPathIsPortfolio || !headingPortfolio || redirectedToLogin, "ASSERTION_FAILED");
  return check.report([], { finalPathIsPortfolio, headingPortfolio, redirectedToLogin, unauthorized401Seen });
}

/** L1: both controls render. Distinguishes a failed portfolio load from a flag that is off. */
async function runL1(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config } = ctx;
  const editButtonVisible = await waitVisible(editHoldingsButton(page), config.actionTimeoutMs);
  const resetButtonVisible = await waitVisible(resetButton(page), Math.min(config.actionTimeoutMs, 10_000));
  const resetEnabled = resetButtonVisible ? await resetButton(page).isEnabled() : false;
  // SETTLED reads only (spec 4.1: an in-flight refetch is not a load outcome). Both values below come from the
  // recorder's state at this one synchronous point (no await between them), so portfolioLoadStatus is always the
  // status of the LAST entry of the http list recorded for this leg, which is what the pass table requires.
  const portfolioReads = recorder.settledPortfolioReads();
  const portfolioLoadStatus = recorder.lastPortfolioStatus();

  const check = new LegCheck();
  if (!editButtonVisible || !resetButtonVisible) {
    // A load failure (status != 200) hides both controls; a 200 with no controls means the flag is off.
    check.fail(portfolioLoadStatus === 200 ? "ASSERTION_FAILED" : failureReasonForStatus(portfolioLoadStatus));
  } else {
    // The pass table (ledger-contract.json passFacts.L1) requires the last portfolio read to be a 200, so
    // a leg that would pass with controls drawn from cached data after a failed refetch fails with its real
    // status instead of being downgraded by the ledger writer.
    check.failIf(portfolioLoadStatus !== 200, failureReasonForStatus(portfolioLoadStatus));
  }
  // Every settled portfolio read so far: a 429 or 503 on any of them fails the leg (see LegRunner).
  return check.report(httpOf(portfolioReads), {
    editButtonVisible,
    resetButtonVisible,
    portfolioLoadStatus,
    resetEnabled,
  });
}

/** L2 (route 9.5): the summary carries a well-formed assetPriceFreshness and the strip renders. */
async function runL2(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config } = ctx;
  const findSummary = (): RecordedRequest | undefined => recorder.find("GET", "/api/portfolio/summary")[0];
  await pollUntil(() => findSummary()?.settled === true, pollOptions(ctx));
  const entry = findSummary();

  const freshness =
    entry?.parsed?.type === "summary" ? entry.parsed.freshness : { state: "ABSENT" as const, countsValid: false };
  const strip = page.getByText(/Prices as of/i).first();
  const stripVisible = entry?.status === 200 ? await waitVisible(strip, config.actionTimeoutMs) : await strip.isVisible();

  const check = new LegCheck();
  if (entry === undefined || !entry.settled) check.fail("TIMEOUT");
  else if (entry.failed || entry.status !== 200) check.fail(failureReasonForStatus(entry.failed ? 0 : entry.status));
  else check.failIf(freshness.state === "ABSENT" || !freshness.countsValid || !stripVisible, "ASSERTION_FAILED");
  return check.report(httpOf(recorder.find("GET", "/api/portfolio/summary")), {
    freshnessState: freshness.state,
    countsValid: freshness.countsValid,
    stripVisible,
  });
}

/** L3: Edit Holdings opens the dialog and no "temporarily unavailable" notice is shown. */
async function runL3(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config, shared } = ctx;
  const dialog = pickerDialog(page);
  const notice = page.getByText(UNAVAILABLE_NOTICE).first();

  await flushPageEvents(page);
  shared.openMark = recorder.mark();
  await editHoldingsButton(page).click({ timeout: config.actionTimeoutMs });
  await pollUntil(async () => (await dialog.isVisible()) || (await notice.isVisible()), pollOptions(ctx));
  const dialogOpen = await dialog.isVisible();
  const unavailableNoticeVisible = await notice.isVisible();

  const check = new LegCheck();
  check.failIf(!dialogOpen || unavailableNoticeVisible, "ASSERTION_FAILED");
  return check.report([], { dialogOpen, unavailableNoticeVisible });
}

/** L4 (route 9.1): the catalog request issued after the click, with no validator, and its rendered effect. */
async function runL4(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config, shared, golden } = ctx;
  const findCatalog = (): RecordedRequest | undefined => recorder.firstAfter(shared.openMark, "GET", "/api/assets");
  await pollUntil(() => findCatalog()?.settled === true, pollOptions(ctx));
  const entry = findCatalog();
  const catalog = entry?.parsed?.type === "catalog" ? entry.parsed.catalog : null;

  const rowsRendered = await waitVisible(pickerDialog(page).getByRole("checkbox").first(), config.actionTimeoutMs);
  const facts = {
    catalogStatus: ledgerStatusOf(entry),
    noIfNoneMatch: entry !== undefined && !entry.hasIfNoneMatch,
    etagPresent: entry?.etagPresent ?? false,
    // catalogVersion present AND assets non-empty and well formed (the two body requirements).
    assetsNonEmpty: catalog?.assetsNonEmpty ?? false,
    rowsRendered,
    catalogParity: catalog !== null && sameTickerSet(catalog.activeTickers, golden.activeTickers),
    activeCount: catalog?.activeTickers.length ?? 0,
  };

  const check = new LegCheck();
  if (entry === undefined || !entry.settled) check.fail("TIMEOUT");
  else if (entry.failed || entry.status !== 200) check.fail(failureReasonForStatus(entry.failed ? 0 : entry.status));
  else {
    check.failIf(
      !facts.noIfNoneMatch ||
        !facts.etagPresent ||
        !facts.assetsNonEmpty ||
        !facts.rowsRendered ||
        !facts.catalogParity,
      "ASSERTION_FAILED",
    );
  }
  return check.report(httpOf(recorder.find("GET", "/api/assets", shared.openMark)), facts);
}

/** L5 (route 9.4): exactly one presence request per opening, judged by its response, not by the banner. */
async function runL5(ctx: LegContext): Promise<LegReport> {
  const { recorder, shared } = ctx;
  const findPresence = (): RecordedRequest[] => recorder.find("GET", "/api/presence/demo", shared.openMark);
  await pollUntil(() => findPresence().some((entry) => entry.settled), pollOptions(ctx));
  // A retry or a second query would show up inside this quiet window.
  await ctx.clock.sleep(QUIET_WINDOW_MS);

  const requests = findPresence();
  const first = requests[0];
  const parsed = first?.parsed?.type === "presence" ? first.parsed.anotherSessionActive : null;
  const requestFailed = recorder.requestFailuresAfter(shared.openMark) > 0;
  const corsConsoleError = recorder.corsConsoleErrorsAfter(shared.openMark) > 0;
  shared.anotherSessionActive = parsed;

  const check = new LegCheck();
  if (first === undefined || !first.settled) check.fail("TIMEOUT");
  else if (first.failed || first.status !== 200) check.fail(failureReasonForStatus(first.failed ? 0 : first.status));
  check.failIf(requestFailed, "REQUEST_FAILED");
  check.failIf(requests.length !== 1 || parsed === null || corsConsoleError, "ASSERTION_FAILED");
  return check.report(httpOf(requests), {
    presenceRequestsSinceOpen: requests.length,
    presenceStatus: ledgerStatusOf(first),
    anotherSessionActive: parsed,
    requestFailed,
    corsConsoleError,
  });
}

/**
 * L6 (route 9.3): uncheck one held ticker in the draft only, so the picker's price batches differ from
 * every batch the page itself requests, then judge the price responses to the picker's own requests
 * emitted afterwards. Only picker-attributed requests count: their number must equal the predicted batch
 * count (a duplicated batch fails), and a page-side price request is neither judged nor recorded here.
 */
async function runL6(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config } = ctx;
  const check = new LegCheck();
  const facts = {
    priceRequestsAfterUncheck: 0,
    allStatus200: false,
    arrayShaped: false,
    nonNullPriceSeen: false,
    disjointFromPageBatches: false,
    predictedBatchCount: 0,
  };

  const held = recorder.lastGoodPortfolio();
  const attribution =
    held === null
      ? null
      : chooseAttributableTicker(
          held.holdings.map((holding) => holding.ticker),
          recorder.portfolioWireOrders(),
        );
  if (held === null || attribution === null) {
    // No held ticker yields disjoint batches: the leg fails closed rather than weakening attribution.
    check.fail("ASSERTION_FAILED");
    return check.report([], facts);
  }
  facts.disjointFromPageBatches = true;
  facts.predictedBatchCount = attribution.predicted.length;

  const dialog = pickerDialog(page);
  // The prediction assumes the draft is exactly the held set.
  const checkedRows = await dialog.getByRole("checkbox", { checked: true }).count();
  if (checkedRows !== held.holdings.length) {
    check.fail("ASSERTION_FAILED");
    return check.report([], facts);
  }

  await flushPageEvents(page);
  const mark = recorder.mark();
  const checkbox = dialog.getByRole("checkbox", { name: `Select ${attribution.ticker}`, exact: true });
  await checkbox.click({ timeout: config.actionTimeoutMs });
  const unchecked = await pollUntil(
    async () => (await checkbox.getAttribute("aria-checked", { timeout: 2_000 })) === "false",
    pollOptions(ctx),
  );

  const evaluate = () => evaluatePriceObservations(recorder.priceObservationsAfter(mark), attribution.predicted);
  await pollUntil(() => evaluate().complete, pollOptions(ctx));
  // Let any further picker request emitted behind the last one arrive and settle before judging.
  await ctx.clock.sleep(1_500);
  await pollUntil(() => evaluate().allSettled, pollOptions(ctx));
  // One snapshot feeds both the verdict and the recorded entries, so they can never disagree.
  const observations = recorder.priceObservationsAfter(mark);
  const evaluation = evaluatePriceObservations(observations, attribution.predicted);

  facts.priceRequestsAfterUncheck = evaluation.priceRequestsAfterUncheck;
  facts.allStatus200 = evaluation.allStatus200;
  facts.arrayShaped = evaluation.arrayShaped;
  facts.nonNullPriceSeen = evaluation.nonNullPriceSeen;

  const failure = priceLegFailure(evaluation, unchecked);
  if (failure !== null) check.fail(failure);

  // Only the attributed picker requests are recorded: every recorded status is judged (a 409, 429, 503
  // or 504 fails the leg), and a page-side failure must not be attributed to route 9.3.
  return check.report(priceHttpEntries(observations, attribution.predicted), facts);
}

/** L7: close the dialog without saving; the page must not have sent any write so far. */
async function runL7(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config } = ctx;
  const dialog = pickerDialog(page);
  await dialog.getByRole("button", { name: "Close", exact: true }).click({ timeout: config.actionTimeoutMs });
  const closed = await waitHidden(dialog, config.actionTimeoutMs);
  const pageWriteRequestsBeforeMutation = recorder.writeRequestCount();

  const check = new LegCheck();
  check.failIf(!closed || pageWriteRequestsBeforeMutation !== 0, "ASSERTION_FAILED");
  return check.report([], { pageWriteRequestsBeforeMutation });
}

/**
 * L8 (route 9.2): reopen for a fresh draft, change exactly one quantity, review, and save once. The
 * write-ahead `armed` event is written immediately before the Save click. Nothing here is retried.
 */
async function runL8(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config, golden, shared } = ctx;
  const timeout = config.actionTimeoutMs;
  const check = new LegCheck();
  const facts = {
    putCount: 0,
    putStatus: 0,
    expectedVersionMatchesObserved: false,
    bodyMatchesExpectedDraft: false,
    versionAdvanced: false,
    savedStatusVisible: false,
    independentReadVersionMatches: false,
    independentReadHoldingsMatch: false,
    mutationSkippedReason: "NONE",
  };
  const bail = (): LegReport => {
    check.fail("ASSERTION_FAILED");
    return check.report([], facts);
  };

  if (recorder.lastGoodPortfolio() === null) return bail();
  const expected = buildExpectedDraft(golden, chooseEditTicker(golden));

  // Reopen: a fresh draft, seeded from the held quantities.
  await editHoldingsButton(page).click({ timeout });
  const dialog = pickerDialog(page);
  const quantityBox = dialog.getByRole("textbox", { name: `${expected.ticker} quantity`, exact: true });
  if (!(await waitVisible(quantityBox, timeout))) return bail();
  if ((await quantityBox.inputValue()) !== expected.fromQuantity) return bail();

  await quantityBox.fill(expected.toQuantity);
  if ((await quantityBox.inputValue()) !== expected.toQuantity) return bail();

  const reviewButton = dialog.getByRole("button", { name: "Review changes", exact: true });
  if (!(await reviewButton.isEnabled())) return bail();
  await reviewButton.click({ timeout });

  // Exactly one changed row; nothing added or removed (golden already holds every active asset).
  const changedOne = await waitVisible(dialog.getByText("Changed — 1", { exact: true }), timeout);
  const otherSections = await dialog.getByText(/^(Added|Removed) — \d+$/).count();
  if (!changedOne || otherSections !== 0) return bail();

  const saveButton = dialog.getByRole("button", { name: "Save changes", exact: true });
  if (!(await waitVisible(saveButton, timeout)) || !(await saveButton.isEnabled())) return bail();

  // Pacing (spec 4.1): the page's own portfolio and price reads must have settled and gone quiet before the write,
  // so its 60 s refetch cannot share a rate-limit second with it. Nothing has been sent yet: giving up here is safe.
  if (!(await waitForQuietPage(ctx))) {
    check.fail("TIMEOUT");
    return check.report([], facts);
  }

  // Point of no return: everything that can be checked without mutating has been. The version the
  // save must carry is the page's most recent server-confirmed read, taken as late as possible.
  const latest = recorder.lastGoodPortfolio();
  if (latest === null) return bail();
  const observedVersion = latest.version;
  await flushPageEvents(page);
  const saveMark = recorder.mark();
  ctx.arm();
  await saveButton.click({ timeout });

  const findPuts = (): RecordedRequest[] => recorder.find("PUT", "/api/portfolio/holdings", saveMark);
  await pollUntil(() => {
    const puts = findPuts();
    return puts.length >= 1 && puts.every((entry) => entry.settled);
  }, pollOptions(ctx));
  // A retry would appear inside this window; it is counted, never issued by us.
  await ctx.clock.sleep(QUIET_WINDOW_MS);

  const puts = findPuts();
  const put = puts[0];
  facts.putCount = puts.length;
  facts.putStatus = ledgerStatusOf(put);

  const requestBody = put === undefined ? undefined : parseRequestJson(put.requestBodyText);
  facts.expectedVersionMatchesObserved = isRecord(requestBody) && requestBody.expectedVersion === observedVersion;
  facts.bodyMatchesExpectedDraft = putBodyMatchesDraft(requestBody, expected.holdings);

  const putResult = put?.parsed?.type === "putResult" ? put.parsed.result : null;
  const saved = putResult !== null && putResult.ok ? putResult.snapshot : null;
  facts.versionAdvanced = saved !== null && saved.version > observedVersion;
  shared.savedVersion = saved === null ? null : saved.version;

  if (facts.putStatus === 200) {
    facts.savedStatusVisible = await waitVisible(statusMessage(page, "Holdings saved."), timeout);
  }
  if (puts.length >= 1) {
    // Out-of-page read: plain HTTP with the token, never the page or its cache.
    const read = await independentPortfolioRead({
      fetchImpl: ctx.fetchImpl,
      apiOrigin: config.apiOrigin,
      token: config.token,
      userId: config.userId,
      timeoutMs: timeout,
    });
    facts.independentReadVersionMatches =
      read.snapshot !== null && saved !== null && read.snapshot.version === saved.version;
    facts.independentReadHoldingsMatch =
      read.snapshot !== null && holdingPairsEqual(read.snapshot.holdings, expected.holdings);
  }

  if (put === undefined) check.fail("TIMEOUT");
  else if (puts.length !== 1) check.fail("ASSERTION_FAILED");
  else if (!put.settled) check.fail("TIMEOUT");
  else if (put.failed || put.status !== 200) check.fail(failureReasonForStatus(put.failed ? 0 : put.status));
  else {
    check.failIf(
      !facts.expectedVersionMatchesObserved ||
        !facts.bodyMatchesExpectedDraft ||
        !facts.versionAdvanced ||
        !facts.savedStatusVisible ||
        !facts.independentReadVersionMatches ||
        !facts.independentReadHoldingsMatch,
      "ASSERTION_FAILED",
    );
  }
  return check.report(httpOf(puts), facts);
}

/**
 * L9 (reset control): press "Reset Demo Portfolio" once. The request must carry the saved version, no
 * internal key, and restore exactly the golden set; the independent read must agree.
 */
async function runL9(ctx: LegContext): Promise<LegReport> {
  const { page, recorder, config, golden, shared } = ctx;
  const timeout = config.actionTimeoutMs;
  const check = new LegCheck();
  const facts = {
    putCount: 0,
    putStatus: 0,
    noInternalKeyHeader: false,
    expectedVersionMatchesSaved: false,
    versionPlusOne: false,
    responseEqualsGolden: false,
    resetStatusVisible: false,
    independentReadEqualsGolden: false,
    mutationSkippedReason: "NONE",
  };
  const savedVersion = shared.savedVersion;
  const button = resetButton(page);
  if (savedVersion === null || !(await waitVisible(button, timeout))) {
    check.fail("ASSERTION_FAILED");
    return check.report([], facts);
  }
  // The control stays disabled until the browser has a server-confirmed version.
  if (!(await pollUntil(() => button.isEnabled(), pollOptions(ctx)))) {
    check.fail("TIMEOUT");
    return check.report([], facts);
  }
  // Pacing (spec 4.1), as before the save: the page's own reads must have settled and gone quiet. Nothing has been
  // sent yet: giving up here is safe.
  if (!(await waitForQuietPage(ctx))) {
    check.fail("TIMEOUT");
    return check.report([], facts);
  }

  await flushPageEvents(page);
  const mark = recorder.mark();
  await button.click({ timeout });

  const findResets = (): RecordedRequest[] => recorder.find("PUT", "/api/portfolio/demo-reset", mark);
  await pollUntil(() => {
    const resets = findResets();
    return resets.length >= 1 && resets.every((entry) => entry.settled);
  }, pollOptions(ctx));
  await ctx.clock.sleep(QUIET_WINDOW_MS);

  const resets = findResets();
  const reset = resets[0];
  facts.putCount = resets.length;
  facts.putStatus = ledgerStatusOf(reset);

  if (reset !== undefined) {
    facts.noInternalKeyHeader = await headerAbsent(reset.request, "x-internal-api-key");
    facts.expectedVersionMatchesSaved = resetBodyMatches(parseRequestJson(reset.requestBodyText), savedVersion);
  }
  const putResult = reset?.parsed?.type === "putResult" ? reset.parsed.result : null;
  const restored = putResult !== null && putResult.ok ? putResult.snapshot : null;
  facts.versionPlusOne = restored !== null && restored.version === savedVersion + 1;
  facts.responseEqualsGolden = restored !== null && holdingPairsEqual(restored.holdings, golden.holdings);

  if (facts.putStatus === 200) {
    facts.resetStatusVisible = await waitVisible(statusMessage(page, "Demo portfolio reset."), timeout);
  }
  if (resets.length >= 1) {
    const read = await independentPortfolioRead({
      fetchImpl: ctx.fetchImpl,
      apiOrigin: config.apiOrigin,
      token: config.token,
      userId: config.userId,
      timeoutMs: timeout,
    });
    facts.independentReadEqualsGolden =
      read.snapshot !== null &&
      restored !== null &&
      read.snapshot.version === restored.version &&
      holdingPairsEqual(read.snapshot.holdings, golden.holdings);
  }

  if (reset === undefined) check.fail("TIMEOUT");
  else if (resets.length !== 1) check.fail("ASSERTION_FAILED");
  else if (!reset.settled) check.fail("TIMEOUT");
  else if (reset.failed || reset.status !== 200) check.fail(failureReasonForStatus(reset.failed ? 0 : reset.status));
  else {
    check.failIf(
      !facts.noInternalKeyHeader ||
        !facts.expectedVersionMatchesSaved ||
        !facts.versionPlusOne ||
        !facts.responseEqualsGolden ||
        !facts.resetStatusVisible ||
        !facts.independentReadEqualsGolden,
      "ASSERTION_FAILED",
    );
  }
  return check.report(httpOf(resets), facts);
}

// -- the test ------------------------------------------------------------------

test("Wave 10.2 Step B 5b: production browser legs L0-L9", async ({ browser }) => {
  // Everything below is read here, never at import time.
  const contract = loadLedgerContract();
  const environment = readStepB5bEnvironment(process.env);
  if (!environment.ok) {
    recordRefusal(contract, environment.workDir);
    throw new Error(`STEP_B_5B refused to start: ${environment.problems.join(", ")}`);
  }
  const config = environment.config;
  if (!isDirectory(config.workDir)) throw new Error("STEP_B_5B refused to start: work directory does not exist");

  let golden: GoldenState;
  try {
    golden = parseGoldenFile(readFileSync(config.goldenFile, "utf8"));
  } catch {
    recordRefusal(contract, config.workDir);
    throw new Error("STEP_B_5B refused to start: golden file is unreadable or invalid");
  }

  const writer = new LedgerWriter({ contract, sink: createFsLedgerSink(path.join(config.workDir, LEDGER_FILE_NAME)) });
  // One clock for everything that measures time here: the recorder stamps when each request settled with it, and
  // the legs poll and pace with it.
  const clock: Clock = realClock;
  const recorder = new PageRecorder({
    origins: { frontend: config.frontendOrigin, api: config.apiOrigin },
    ledgerPaths: contract.paths,
    userId: config.userId,
    now: () => clock.now(),
  });
  // A bearer-carrying request to any other origin fails every leg recorded after it, and finish()
  // evaluates the guard again so one that appears after the last leg still fails the run.
  const runner = new LegRunner({ writer, guard: () => recorder.foreignAuthorizedRequestSeen() });
  const shared: SharedState = { openMark: 0, anotherSessionActive: null, savedVersion: null };
  let context: BrowserContext | undefined;

  try {
    // A private, empty context: no shared storage state, no service workers, no downloads.
    context = await browser.newContext({
      serviceWorkers: "block",
      acceptDownloads: false,
      storageState: { cookies: [], origins: [] },
      viewport: { width: 1280, height: 900 },
    });
    context.setDefaultTimeout(config.actionTimeoutMs);
    context.setDefaultNavigationTimeout(config.actionTimeoutMs);
    await context.addInitScript(installSession, {
      key: AUTH_STORAGE_KEY,
      value: JSON.stringify({
        token: config.token,
        userId: config.userId,
        email: config.email,
        name: SESSION_DISPLAY_NAME,
      }),
      origin: config.frontendOrigin,
    });
    const page = await context.newPage();
    // Listeners exist before the first navigation, so none can miss the request it triggers.
    attachRecorder(page, recorder);

    const ctx: LegContext = {
      page,
      recorder,
      config,
      golden,
      clock,
      fetchImpl: (url, init) => fetch(url, init),
      shared,
      arm: () => writer.writeArmed(),
    };

    await runner.run("L0", () => runL0(ctx));
    await runner.run("L1", () => runL1(ctx));
    await runner.run("L2", () => runL2(ctx));
    await runner.run("L3", () => runL3(ctx));
    await runner.run("L4", () => runL4(ctx));
    await runner.run("L5", () => runL5(ctx));
    await runner.run("L6", () => runL6(ctx));
    await runner.run("L7", () => runL7(ctx));

    // Mutate only if L0-L7 passed, the baseline is known and is what the page sees, and no other
    // demo session is active (unless the owner allowed it).
    const observed = recorder.lastGoodPortfolio();
    const gate = decideMutationGate({
      priorLegsPassed: !runner.failed,
      baselineVersionSupplied: config.baselineVersion !== null,
      pageMatchesBaseline:
        observed !== null &&
        config.baselineVersion !== null &&
        observed.version === config.baselineVersion &&
        holdingPairsEqual(observed.holdings, golden.holdings),
      anotherSessionActive: shared.anotherSessionActive,
      allowActivePresence: config.allowActivePresence,
    });
    if (gate.run) {
      await runner.run("L8", () => runL8(ctx));
      await runner.run("L9", () => runL9(ctx));
    } else {
      runner.skip("L8", gate.legReason);
      runner.skip("L9", gate.legReason);
    }
  } finally {
    // Every leg gets exactly one final event, whatever happened above; the orchestrator cleans up regardless.
    runner.finish();
    await context?.close().catch(() => undefined);
  }

  // The ledger, not this exit code, is authoritative; a red test simply agrees with it. finish() has
  // already re-evaluated the run-wide guard, so a bearer-carrying request to a non-allowlisted origin
  // after the last leg still ends in a non-zero exit (the orchestrator treats that as NON_GO).
  if (runner.guardTripped) throw new Error("STEP_B_5B: a bearer-carrying request reached a non-allowlisted origin");
  if (runner.failed) throw new Error("STEP_B_5B: at least one leg did not pass (see the ledger)");
});
