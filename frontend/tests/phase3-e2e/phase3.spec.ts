/**
 * Phase 3 multi-user browser suite (design:
 * docs/superpowers/specs/2026-09-22-phase3-multi-user-e2e-suite-design.md).
 *
 * Scenarios run in declaration order in one worker. They are NOT serial-mode: after a
 * failure Playwright restarts the worker and continues, so later scenarios (and S99's
 * restoration) still run. Each scenario re-establishes its own preconditions.
 */
import { execSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Browser, type BrowserContext, type Request, type Route, type TestInfo, type Video } from "@playwright/test";
import { ApiClient, type AnalyticsReadback, type AuthSession, type SummaryReadback } from "./lib/api";
import { msSinceLastWriteBefore, staleAnalyticsExpiresBy, withinAnalyticsCacheWindow } from "./lib/cache-window";
import { provenanceEnvironment, scanArtifactsForSecrets } from "./lib/artifacts";
import { fileStore, paceAuthRequest } from "./lib/auth-pacer";
import { resolveRunConfig } from "./lib/config";
import { ContextMonitor, Evidence, type RouteHook } from "./lib/evidence";
import {
  BASELINES,
  CERT_A_BASELINE,
  CERT_B_AFTER_S08,
  CERT_B_AFTER_S09,
  CERT_B_BASELINE,
  DISPLAY_NAMES,
  ensureHoldings,
  FRESH_TARGET,
  tickersOf,
  type Role,
} from "./lib/roles";
import {
  addAsset,
  captureCalls,
  hasNoHorizontalOverflow,
  installLeakObserver,
  marketTableTickers,
  openPicker,
  parseDisplayedPercent,
  portfolioTableTickers,
  quantityBox,
  readAgainstLatest,
  removeAsset,
  requestsAfterRetryWindow,
  reviewCounts,
  rowCellsByTicker,
  signInByStorage,
  singleResponse,
  storedSession,
  waitForPortfolioPage,
} from "./lib/ui";
import { holdingsEqual, normalizeDecimal, normalizeHoldings, parseDisplayedMoney } from "./lib/values";
import { scenarioIdOf } from "./lib/verdict";

// ── Run setup ────────────────────────────────────────────────────────────────

const REPO_ROOT = path.resolve(__dirname, "../../..");
const resolved = resolveRunConfig(process.env, { repoRoot: REPO_ROOT, now: new Date(), randomHex: () => "0000" });
if (!resolved.ok) throw new Error(`[phase3] refusing to run: ${resolved.problems.join(", ")}`);
const run = resolved.config;
const RUN_DIR = process.env.P3_RUN_DIR as string;
const evidence = new Evidence(RUN_DIR);
const secretValues: string[] = [run.certA.password, run.certB.password, run.fresh.password];
secretValues.forEach((secret) => evidence.secrets.register(secret));
const registerSecret = (secret: string) => {
  secretValues.push(secret);
  evidence.secrets.register(secret);
};

const DESKTOP_VIEWPORTS = [
  { width: 1280, height: 800 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
] as const;
const MONEY_TOLERANCE = 0.005 + 1e-9;
/** Auth requests are held by the pacer (>= 13 s in Production) before they leave the browser. */
const AUTH_WAIT_MS = run.authMinIntervalMs + 20_000;
const PERCENT_TOLERANCE = 0.005 + 1e-9;

const pacerStore = fileStore(path.join(RUN_DIR, "auth-pacer.json"));
const AUTH_LOG = path.join(RUN_DIR, "auth-requests.jsonl");
const recordAuth = (source: "api" | "browser", pathname: string) =>
  appendFileSync(AUTH_LOG, `${JSON.stringify({ at: new Date().toISOString(), scenario: currentScenario, source, pathname })}\n`);
const paceAuth = async () => {
  await paceAuthRequest(pacerStore, run.authMinIntervalMs);
};
const api = new ApiClient(run.api, async () => {
  await paceAuth();
  recordAuth("api", "/api/auth/*");
});

// Every successful holdings write (Node setup and browser saves), so S11 can tell the known
// analytics-cache staleness (writes within its 30 s TTL) from a real divergence.
const HOLDINGS_WRITES = path.join(RUN_DIR, "holdings-writes.jsonl");
const recordHoldingsWrite = (userId: string) =>
  appendFileSync(HOLDINGS_WRITES, `${JSON.stringify({ at: Date.now(), userId, scenario: currentScenario })}\n`);
function holdingsWriteTimes(userId: string): number[] {
  if (!existsSync(HOLDINGS_WRITES)) return [];
  return readFileSync(HOLDINGS_WRITES, "utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line) as { at: number; userId: string })
    .filter((entry) => entry.userId === userId)
    .map((entry) => entry.at);
}
/** Waits until every analytics entry cached before the user's latest write (at or before `readAtMs`) has expired. */
async function waitOutStaleAnalytics(userId: string, readAtMs = Date.now()): Promise<number> {
  const expiry = staleAnalyticsExpiresBy(holdingsWriteTimes(userId), readAtMs);
  const waitMs = expiry === null ? 0 : Math.max(0, expiry - Date.now());
  if (waitMs > 0) await new Promise((resolve) => setTimeout(resolve, waitMs));
  return waitMs;
}
async function ensure(session: AuthSession, desired: Parameters<typeof ensureHoldings>[2]) {
  const result = await ensureHoldings(api, session, desired);
  if (result.outcome === "written") recordHoldingsWrite(session.userId);
  return result;
}

const lastStrictRequest = new Map<string, number>();
async function paceStrict(userKey: string): Promise<void> {
  if (run.strictMinIntervalMs <= 0) return;
  const last = lastStrictRequest.get(userKey);
  const wait = last === undefined ? 0 : run.strictMinIntervalMs - (Date.now() - last);
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  lastStrictRequest.set(userKey, Date.now());
}

const allowedAuthEmails = new Set([run.certA.email, run.certB.email, run.fresh.email].map((e) => e.toLowerCase()));

let currentScenario = "S00";
test.beforeEach(({}, testInfo) => {
  currentScenario = scenarioIdOf(testInfo.title) ?? "S??";
});

// ── Sessions (lazy; a restarted worker re-authenticates through the pacer) ───

const sessions: Partial<Record<Role, AuthSession>> = {};

async function certSession(role: "CERT_A" | "CERT_B"): Promise<AuthSession> {
  const cached = sessions[role];
  if (cached) return cached;
  const credentials = role === "CERT_A" ? run.certA : run.certB;
  let session = await api.login(credentials);
  if (!session) {
    if (!run.allowProvision) throw new Error(`${role} is not provisioned (login 401); production never provisions`);
    session = await api.signup(credentials, DISPLAY_NAMES[role]);
  }
  registerSecret(session.token);
  sessions[role] = session;
  return session;
}

async function freshSession(): Promise<AuthSession> {
  const cached = sessions.FRESH;
  if (cached) return cached;
  const session = await api.login(run.fresh);
  if (!session) throw new Error("FRESH does not exist: S02 did not create it");
  registerSecret(session.token);
  sessions.FRESH = session;
  return session;
}

function rememberFresh(session: AuthSession | null): void {
  if (!session) return;
  registerSecret(session.token);
  sessions.FRESH = session;
}

// ── Contexts ─────────────────────────────────────────────────────────────────

interface Monitored {
  readonly context: BrowserContext;
  readonly monitor: ContextMonitor;
  readonly label: string;
}

const openContexts: Monitored[] = [];
let contextCounter = 0;
const PW_OUTPUT = path.join(RUN_DIR, "pw-output");

/**
 * Traces and videos are managed explicitly per context. The runner's implicit tracing
 * of contexts created with browser.newContext() hung at teardown whenever the context
 * ended on a live dashboard (truncated trace.zip, test timeout), so the config turns it
 * off. Every context keeps its trace; videos are kept only when the test failed.
 */
async function finalizeContext(monitored: Monitored, testInfo: TestInfo): Promise<void> {
  const index = openContexts.indexOf(monitored);
  if (index >= 0) openContexts.splice(index, 1);
  const { context, label } = monitored;
  const videos = context.pages().map((page) => page.video()).filter((video): video is Video => video !== null);
  await context.tracing.stop({ path: path.join(PW_OUTPUT, "traces", `${label}.zip`) }).catch((error: unknown) => {
    evidence.observe(currentScenario, "trace could not be saved", { context: label, error: String(error).slice(0, 160) });
  });
  await context.close().catch(() => undefined);
  const failed = testInfo.status !== testInfo.expectedStatus;
  for (const [i, video] of videos.entries()) {
    if (failed) {
      await video.saveAs(path.join(PW_OUTPUT, "videos", `${label}-${i + 1}.webm`)).catch((error: unknown) => {
        evidence.observe(currentScenario, "failure video could not be saved", { context: label, error: String(error).slice(0, 160) });
      });
    }
    await video.delete().catch(() => undefined);
  }
}

async function monitoredContext(
  browser: Browser,
  role: string,
  options: { viewport?: { width: number; height: number }; routeHook?: RouteHook } = {},
): Promise<Monitored> {
  const context = await browser.newContext({
    baseURL: run.frontend,
    viewport: options.viewport ?? { width: 1440, height: 900 },
    serviceWorkers: "block",
    recordVideo: { dir: path.join(PW_OUTPUT, "video-tmp") },
  });
  contextCounter += 1;
  const label = `${currentScenario}-${String(contextCounter).padStart(2, "0")}-${role.replace(/[^A-Za-z0-9_-]/g, "_")}`;
  try {
    await context.tracing.start({ screenshots: true, snapshots: true, title: label });
    if (run.negativeControl === "NC1" && currentScenario === "S02") {
      await context.addInitScript(() => {
        setTimeout(() => {
          throw new Error("P3-NC1 injected page error");
        }, 0);
      });
    }
    const monitor = await ContextMonitor.attach(context, {
      role,
      frontend: run.frontend,
      api: run.api,
      evidence,
      currentScenario: () => currentScenario,
      allowedAuthEmails,
      paceAuth,
      paceStrict,
      onAuthRequest: (pathname) => recordAuth("browser", pathname),
      onHoldingsWrite: recordHoldingsWrite,
      routeHook: options.routeHook,
    });
    const monitored = { context, monitor, label };
    openContexts.push(monitored);
    return monitored;
  } catch (error) {
    await context.close().catch(() => undefined);
    throw error;
  }
}

test.afterEach(async ({}, testInfo) => {
  // Close every context so refetch intervals stop and cannot spend rate-limit budget.
  for (const monitored of [...openContexts]) await finalizeContext(monitored, testInfo);
});

function fulfillJson(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function isApi(request: Request, method: string, pathname: string): boolean {
  const url = new URL(request.url());
  return url.origin === run.api && request.method() === method && url.pathname === pathname;
}

// ── S00 ──────────────────────────────────────────────────────────────────────

test("S00 preflight: served build, identities, declared baselines", async () => {
  const html = await (await fetch(`${run.frontend}/login`)).text();
  const buildId = /\\"b\\":\\"([A-Za-z0-9_-]{10,})\\"/.exec(html)?.[1] ?? null;
  evidence.verify("S00", "served frontend build ID is readable", buildId !== null, { buildId });
  if (process.env.P3_EXPECTED_BUILD_ID) {
    evidence.verify("S00", "served build ID equals the expected candidate", buildId === process.env.P3_EXPECTED_BUILD_ID, {
      buildId,
      expected: process.env.P3_EXPECTED_BUILD_ID,
    });
  }

  for (const role of ["CERT_A", "CERT_B"] as const) {
    const session = await certSession(role);
    const result = await ensure(session, BASELINES[role]);
    evidence.observe("S00", "prior state before normalization", {
      role,
      priorVersion: result.before.version,
      priorHoldings: normalizeHoldings(result.before.holdings),
      normalization: result.outcome,
    });
    evidence.verify("S00", `${role} is at its declared baseline`, holdingsEqual(result.after.holdings, BASELINES[role]), {
      version: result.after.version,
    });
  }

  const gitSha = execSync("git rev-parse HEAD", { cwd: REPO_ROOT }).toString().trim();
  const gitDirty = execSync("git status --porcelain", { cwd: REPO_ROOT }).toString().trim().length > 0;
  evidence.writeJson("provenance.json", {
    target: run.mode,
    runId: run.runId,
    frontendOrigin: run.frontend,
    apiOrigin: run.api,
    servedBuildId: buildId,
    repoVersion: readFileSync(path.join(REPO_ROOT, "VERSION"), "utf8").trim(),
    suiteGitSha: gitSha,
    suiteTreeDirty: gitDirty,
    startedAt: new Date().toISOString(),
    viewports: DESKTOP_VIEWPORTS,
    node: process.version,
    environment: provenanceEnvironment(process.env),
  });
});

// ── S01–S04: signup, login, logout ───────────────────────────────────────────

test("S01 signup validation blocks bad input without sending a request", async ({ browser }) => {
  const { context, monitor } = await monitoredContext(browser, "ANON");
  const page = await context.newPage();
  const signups = captureCalls(context, "POST", "/api/auth/signup");
  await page.goto("/signup");
  await expect(page.getByRole("heading", { name: "Create an account" })).toBeVisible();

  const cases = [
    { label: "email without a dot", name: DISPLAY_NAMES.FRESH, email: "a@b", password: run.fresh.password, message: "Enter a valid email address." },
    { label: "11-character password", name: DISPLAY_NAMES.FRESH, email: run.fresh.email, password: "short-pw-11", message: "Password must be at least 12 characters (max 72 bytes)." },
    { label: "whitespace-only name", name: "   ", email: run.fresh.email, password: run.fresh.password, message: "Name is required." },
  ];
  for (const [index, c] of cases.entries()) {
    await page.getByLabel("Name", { exact: true }).fill(c.name);
    await page.getByLabel("Email", { exact: true }).fill(c.email);
    await page.getByLabel("Password", { exact: true }).fill(c.password);
    await page.getByRole("button", { name: "Create account" }).click();
    const shown = await page
      .getByText(c.message, { exact: true })
      .waitFor({ state: "visible", timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    evidence.verify("S01", `shows the validation message for ${c.label}`, shown, { expected: c.message });
    await evidence.screenshot(page, "S01", `case-${index + 1}`);
  }
  evidence.verify("S01", "no signup request was sent", signups.requests.length === 0, { signupRequests: signups.requests.length });
  evidence.verify("S01", "the page stayed on /signup", new URL(page.url()).pathname === "/signup");
  monitor.assertClean("S01");
});

test("S02 successful signup lands on an empty portfolio", async ({ browser }) => {
  const { context, monitor } = await monitoredContext(browser, "FRESH");
  const page = await context.newPage();
  const signups = captureCalls(context, "POST", "/api/auth/signup");
  await page.goto("/signup");
  await page.getByLabel("Name", { exact: true }).fill(DISPLAY_NAMES.FRESH);
  await page.getByLabel("Email", { exact: true }).fill(run.fresh.email);
  await page.getByLabel("Password", { exact: true }).fill(run.fresh.password);
  evidence.verify("S02", "email field holds FRESH's email before submit", (await page.getByLabel("Email", { exact: true }).inputValue()) === run.fresh.email);
  await page.getByRole("button", { name: "Create account" }).click();

  const response = await singleResponse(signups, AUTH_WAIT_MS);
  evidence.verify("S02", "exactly one signup request, answered 201", response.status() === 201, { status: response.status() });
  await expect(page).toHaveURL(/\/overview$/, { timeout: AUTH_WAIT_MS });
  const stored = await storedSession(page);
  rememberFresh(stored);
  evidence.verify("S02", "the browser stored FRESH's session", stored !== null && stored.email.toLowerCase() === run.fresh.email && stored.name === DISPLAY_NAMES.FRESH);
  await expect(page.getByRole("button", { name: "User menu" })).toContainText(DISPLAY_NAMES.FRESH);

  const total = page.getByTestId("total-value");
  await expect(total).toBeVisible();
  evidence.verify("S02", "Overview total is $0.00", parseDisplayedMoney((await total.textContent()) ?? "") === 0, {
    shown: await total.textContent(),
  });
  await evidence.screenshot(page, "S02", "overview-empty");

  await page.getByRole("link", { name: "Portfolio", exact: true }).click();
  await expect(page).toHaveURL(/\/portfolio$/);
  await expect(page.locator("main table")).toBeVisible();
  await expect(page.locator("main table tbody tr")).toHaveCount(1);
  const emptyCopy = ((await page.locator("main table tbody tr").textContent()) ?? "").trim();
  evidence.verify("S02", "Portfolio holdings table has no holding rows", (await portfolioTableTickers(page)).length === 0);
  evidence.observe("S02", "empty-portfolio copy (UX finding for triage)", {
    shown: emptyCopy,
    finding: emptyCopy === "No holdings match your filter." ? "empty-portfolio-shows-filter-copy" : null,
  });
  await evidence.screenshot(page, "S02", "portfolio-empty");

  await page.getByRole("link", { name: "Market Data", exact: true }).click();
  await expect(page).toHaveURL(/\/market-data$/);
  await expect(page.getByText("No market data available.", { exact: true })).toBeVisible();
  evidence.verify("S02", "Market Data shows its empty state", true);
  await evidence.screenshot(page, "S02", "market-data-empty");

  const readback = await api.portfolio(stored as AuthSession);
  evidence.verify("S02", "independent readback: version 0, no holdings", readback.version === 0 && readback.holdings.length === 0, {
    version: readback.version,
    holdings: readback.holdings.length,
  });
  monitor.assertClean("S02");
});

test("S03 duplicate signup with different email case is rejected", async ({ browser }) => {
  const { context, monitor } = await monitoredContext(browser, "ANON");
  const page = await context.newPage();
  const signups = captureCalls(context, "POST", "/api/auth/signup");
  await page.goto("/signup");
  await page.getByLabel("Name", { exact: true }).fill(DISPLAY_NAMES.FRESH);
  await page.getByLabel("Email", { exact: true }).fill(run.fresh.email.toUpperCase());
  await page.getByLabel("Password", { exact: true }).fill(run.fresh.password);
  evidence.verify("S03", "email field holds FRESH's email (other case) before submit", (await page.getByLabel("Email", { exact: true }).inputValue()) === run.fresh.email.toUpperCase());
  monitor.declare({ method: "POST", pathname: "/api/auth/signup", status: 409 });
  await page.getByRole("button", { name: "Create account" }).click();

  const response = await singleResponse(signups, AUTH_WAIT_MS);
  evidence.verify("S03", "exactly one signup request, answered 409", response.status() === 409, { status: response.status() });
  await expect(page.getByText("An account with this email already exists.", { exact: true })).toBeVisible();
  evidence.verify("S03", "duplicate-account message shown", true);
  evidence.verify("S03", "stayed on /signup", new URL(page.url()).pathname === "/signup");
  evidence.verify("S03", "no session stored", (await storedSession(page)) === null);
  await evidence.screenshot(page, "S03", "duplicate");
  monitor.assertClean("S03");
});

test("S04 login, logout and re-login", async ({ browser }) => {
  const { context, monitor } = await monitoredContext(browser, "FRESH");
  const page = await context.newPage();
  const logins = captureCalls(context, "POST", "/api/auth/login");
  await page.goto("/login");
  const email = page.getByLabel("Email", { exact: true });
  const password = page.getByLabel("Password", { exact: true });

  await email.fill(run.fresh.email);
  await password.fill(`wrong-${randomBytes(8).toString("hex")}`);
  evidence.verify("S04", "email field holds FRESH's email before submit", (await email.inputValue()) === run.fresh.email);
  monitor.declare({ method: "POST", pathname: "/api/auth/login", status: 401 });
  await page.getByRole("button", { name: "Sign in" }).click();
  const wrong = await singleResponse(logins, AUTH_WAIT_MS);
  evidence.verify("S04", "wrong password answered 401", wrong.status() === 401, { status: wrong.status() });
  await expect(page.getByText("Invalid username or password.", { exact: true })).toBeVisible();
  evidence.verify("S04", "invalid-credentials message shown; no session", (await storedSession(page)) === null);

  await password.fill(run.fresh.password);
  evidence.verify("S04", "email field still holds FRESH's email", (await email.inputValue()) === run.fresh.email);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/overview$/, { timeout: AUTH_WAIT_MS });
  const first = await storedSession(page);
  rememberFresh(first);
  evidence.verify("S04", "login stored FRESH's session", first !== null && first.email.toLowerCase() === run.fresh.email);
  await expect(page.getByRole("button", { name: "User menu" })).toContainText(DISPLAY_NAMES.FRESH);

  await page.getByRole("button", { name: "User menu" }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  evidence.verify("S04", "sign-out cleared the stored session", (await storedSession(page)) === null);
  await page.goto("/portfolio");
  await expect(page).toHaveURL(/\/login$/, { timeout: 20_000 });
  evidence.verify("S04", "a protected page redirects to /login after sign-out", true);

  const afterLogout = await fetch(`${run.api}/api/portfolio`, { headers: { Authorization: `Bearer ${first!.token}` } });
  evidence.observe("S04", "D7: pre-logout token after sign-out", {
    status: afterLogout.status,
    stillAccepted: afterLogout.status === 200,
  });

  await email.fill(run.fresh.email);
  await password.fill(run.fresh.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/overview$/, { timeout: AUTH_WAIT_MS });
  const second = await storedSession(page);
  rememberFresh(second);
  evidence.verify("S04", "re-login restores the same user", second !== null && second.userId === first!.userId);
  evidence.verify("S04", "three login requests in total", logins.requests.length === 3, { count: logins.requests.length });
  await evidence.screenshot(page, "S04", "relogin");
  monitor.assertClean("S04");
});

// ── S05–S08: portfolio mutations ─────────────────────────────────────────────

test("S05 empty portfolio → add first holdings, save, persisted readback", async ({ browser }) => {
  const fresh = await freshSession();
  const before = await api.portfolio(fresh);
  evidence.verify("S05", "precondition: FRESH is empty", before.holdings.length === 0, { version: before.version });

  const routeHook: RouteHook | undefined =
    run.negativeControl === "NC2"
      ? async (route, request) => {
          if (!isApi(request, "PUT", "/api/portfolio/holdings")) return false;
          const body = request.postDataJSON() as { holdings: Array<{ ticker: string; quantity: string }> };
          await fulfillJson(route, 200, {
            id: "nc2",
            userId: fresh.userId,
            version: before.version + 1,
            holdings: body.holdings.map((h, i) => ({ id: `nc2-${i}`, assetTicker: h.ticker, quantity: h.quantity })),
          });
          return true;
        }
      : undefined;
  const { context, monitor } = await monitoredContext(browser, "FRESH", { routeHook });
  const page = await context.newPage();
  await page.clock.install();
  const puts = captureCalls(context, "PUT", "/api/portfolio/holdings");
  await signInByStorage(page, fresh, "/portfolio");
  await waitForPortfolioPage(page);

  const dialog = await openPicker(page);
  await addAsset(dialog, "GOOGL", "7");
  await addAsset(dialog, "DOGE-USD", "1500.5");
  await dialog.getByRole("button", { name: "Review changes" }).click();
  const counts = await reviewCounts(dialog);
  evidence.verify("S05", "review shows exactly two additions", counts.Added === 2 && counts.Changed === 0 && counts.Removed === 0 && counts.Unchanged === 0, counts);
  await evidence.screenshot(page, "S05", "review");
  await dialog.getByRole("button", { name: "Save changes" }).click();

  const response = await singleResponse(puts);
  const sent = puts.requests[0].postDataJSON() as { expectedVersion: number; holdings: Array<{ ticker: string; quantity: string }> };
  evidence.verify("S05", "one PUT answered 200", response.status() === 200, { status: response.status() });
  evidence.verify("S05", "PUT carried the loaded version and the typed quantities", sent.expectedVersion === before.version && holdingsEqual(sent.holdings, FRESH_TARGET) && sent.holdings.every((h) => ["7", "1500.5"].includes(h.quantity)), {
    expectedVersion: sent.expectedVersion,
  });
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("status")).toHaveText("Holdings saved.");

  const after = await api.portfolio(fresh);
  evidence.verify("S05", "independent readback equals the saved draft with version +1", holdingsEqual(after.holdings, FRESH_TARGET) && after.version === before.version + 1, {
    version: after.version,
    holdings: normalizeHoldings(after.holdings),
  });
  await expect.poll(() => portfolioTableTickers(page)).toEqual(tickersOf(FRESH_TARGET));
  evidence.verify("S05", "Portfolio table shows the saved holdings", true);
  evidence.verify("S05", "no automatic retry within the retry window", (await requestsAfterRetryWindow(page, () => puts.requests.length)) === 1);
  await evidence.screenshot(page, "S05", "after-save");
  monitor.assertClean("S05");
});

test("S06 cancellation discards the draft without writing", async ({ browser }) => {
  const certA = await certSession("CERT_A");
  await ensure(certA, CERT_A_BASELINE);
  const before = await api.portfolio(certA);

  let summaryFaulted = false;
  const routeHook: RouteHook | undefined =
    run.negativeControl === "NC4"
      ? async (route, request) => {
          if (summaryFaulted || !isApi(request, "GET", "/api/portfolio/summary")) return false;
          summaryFaulted = true;
          await fulfillJson(route, 500, { error: "nc4" });
          return true;
        }
      : undefined;
  const { context, monitor } = await monitoredContext(browser, "CERT_A", { routeHook });
  const page = await context.newPage();
  const puts = captureCalls(context, "PUT", "/api/portfolio/holdings");
  await signInByStorage(page, certA, "/portfolio");
  await waitForPortfolioPage(page);

  let dialog = await openPicker(page);
  evidence.verify("S06", "AAPL starts at the persisted 12", normalizeDecimal(await quantityBox(dialog, "AAPL").inputValue()) === "12");
  await quantityBox(dialog, "AAPL").fill("13");
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  dialog = await openPicker(page);
  evidence.verify("S06", "Escape discarded the AAPL edit", normalizeDecimal(await quantityBox(dialog, "AAPL").inputValue()) === "12");
  await quantityBox(dialog, "MSFT").fill("9");
  await dialog.getByRole("button", { name: "Close" }).click();
  await expect(dialog).toBeHidden();

  dialog = await openPicker(page);
  evidence.verify("S06", "the Close button discarded the MSFT edit", normalizeDecimal(await quantityBox(dialog, "MSFT").inputValue()) === "4.5");
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  evidence.verify("S06", "no PUT was sent", puts.requests.length === 0, { puts: puts.requests.length });
  const after = await api.portfolio(certA);
  evidence.verify("S06", "readback and version unchanged", holdingsEqual(after.holdings, CERT_A_BASELINE) && after.version === before.version, {
    version: after.version,
  });
  monitor.assertClean("S06");
});

test("S07 invalid quantities block review and are announced", async ({ browser }) => {
  const certA = await certSession("CERT_A");
  await ensure(certA, CERT_A_BASELINE);
  const before = await api.portfolio(certA);
  const { context, monitor } = await monitoredContext(browser, "CERT_A");
  const page = await context.newPage();
  const puts = captureCalls(context, "PUT", "/api/portfolio/holdings");
  await signInByStorage(page, certA, "/portfolio");
  await waitForPortfolioPage(page);
  const dialog = await openPicker(page);
  const box = quantityBox(dialog, "AAPL");
  const review = dialog.getByRole("button", { name: "Review changes" });

  const cases: Array<[string, string]> = [
    ["0", "Quantity must be greater than zero."],
    ["-1", "Quantity must be greater than zero."],
    ["abc", "Enter a quantity as a plain decimal number, for example 12.5."],
    ["1.123456789", "Quantity may have at most 8 digits after the decimal point."],
    ["123456789012", "Quantity may have at most 11 digits before the decimal point."],
    ["", "Quantity is required."],
  ];
  for (const [value, message] of cases) {
    await box.fill(value);
    await expect(box).toHaveAttribute("aria-invalid", "true");
    await expect(box).toHaveAccessibleDescription(message);
    await expect(review).toBeDisabled();
    evidence.verify("S07", `quantity ${JSON.stringify(value)} is invalid, announced and blocks review`, true, { message });
  }
  await evidence.screenshot(page, "S07", "invalid");
  await box.fill("12");
  await expect(box).not.toHaveAttribute("aria-invalid", "true");
  await expect(review).toBeEnabled();
  evidence.verify("S07", "a valid quantity clears the error and re-enables review", true);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  evidence.verify("S07", "no PUT was sent", puts.requests.length === 0);
  const after = await api.portfolio(certA);
  evidence.verify("S07", "readback and version unchanged", holdingsEqual(after.holdings, CERT_A_BASELINE) && after.version === before.version);
  monitor.assertClean("S07");
});

test("S08 update, remove and add in one reviewed save", async ({ browser }) => {
  const certB = await certSession("CERT_B");
  await ensure(certB, CERT_B_BASELINE);
  const before = await api.portfolio(certB);
  const { context, monitor } = await monitoredContext(browser, "CERT_B");
  const page = await context.newPage();
  const puts = captureCalls(context, "PUT", "/api/portfolio/holdings");
  await signInByStorage(page, certB, "/portfolio");
  await waitForPortfolioPage(page);

  const dialog = await openPicker(page);
  await quantityBox(dialog, "ETH-USD").fill("3.5");
  await removeAsset(dialog, "SOL-USD");
  await addAsset(dialog, "ADA-USD", "250");
  await dialog.getByRole("button", { name: "Review changes" }).click();
  const counts = await reviewCounts(dialog);
  evidence.verify("S08", "review shows 1 added, 1 changed, 1 removed, 2 unchanged", counts.Added === 1 && counts.Changed === 1 && counts.Removed === 1 && counts.Unchanged === 2, counts);
  await evidence.screenshot(page, "S08", "review");
  await dialog.getByRole("button", { name: "Save changes" }).click();

  const response = await singleResponse(puts);
  const sent = puts.requests[0].postDataJSON() as { expectedVersion: number; holdings: Array<{ ticker: string; quantity: string }> };
  evidence.verify("S08", "one PUT answered 200 with the full desired set", response.status() === 200 && sent.expectedVersion === before.version && holdingsEqual(sent.holdings, CERT_B_AFTER_S08), {
    status: response.status(),
  });
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("status")).toHaveText("Holdings saved.");
  const after = await api.portfolio(certB);
  evidence.verify("S08", "independent readback exact with version +1", holdingsEqual(after.holdings, CERT_B_AFTER_S08) && after.version === before.version + 1, {
    version: after.version,
    holdings: normalizeHoldings(after.holdings),
  });
  await expect.poll(() => portfolioTableTickers(page)).toEqual(tickersOf(CERT_B_AFTER_S08));
  evidence.verify("S08", "Portfolio table matches the readback", true);
  await evidence.screenshot(page, "S08", "after-save");
  monitor.assertClean("S08");
});

// ── S09: optimistic concurrency ──────────────────────────────────────────────

test("S09 conflicting save from a second session is rejected without retry or overwrite", async ({ browser }) => {
  const session1 = await certSession("CERT_B");
  await ensure(session1, CERT_B_AFTER_S08);
  const before = await api.portfolio(session1);
  const session2 = await api.login(run.certB);
  if (!session2) throw new Error("CERT_B second login failed");
  registerSecret(session2.token);
  evidence.verify("S09", "the two sessions hold distinct tokens", session1.token !== session2.token);
  const certA = await certSession("CERT_A");
  const certABefore = await api.portfolio(certA);

  const first = await monitoredContext(browser, "CERT_B#1");
  const second = await monitoredContext(browser, "CERT_B#2");
  const page1 = await first.context.newPage();
  const page2 = await second.context.newPage();
  await page2.clock.install();
  const puts1 = captureCalls(first.context, "PUT", "/api/portfolio/holdings");
  const puts2 = captureCalls(second.context, "PUT", "/api/portfolio/holdings");
  await signInByStorage(page1, session1, "/portfolio");
  await signInByStorage(page2, session2, "/portfolio");
  await waitForPortfolioPage(page1);
  await waitForPortfolioPage(page2);

  const dialog1 = await openPicker(page1);
  const dialog2 = await openPicker(page2);
  evidence.verify("S09", "both pickers opened on the same persisted state", normalizeDecimal(await quantityBox(dialog1, "TSLA").inputValue()) === "6" && normalizeDecimal(await quantityBox(dialog2, "TSLA").inputValue()) === "6");

  await quantityBox(dialog1, "TSLA").fill("7");
  await dialog1.getByRole("button", { name: "Review changes" }).click();
  await dialog1.getByRole("button", { name: "Save changes" }).click();
  const saved = await singleResponse(puts1);
  evidence.verify("S09", "session 1 save answered 200", saved.status() === 200, { status: saved.status() });
  await expect(dialog1).toBeHidden();

  await quantityBox(dialog2, "BTC-USD").fill("0.5");
  await dialog2.getByRole("button", { name: "Review changes" }).click();
  second.monitor.declare({ method: "PUT", pathname: "/api/portfolio/holdings", status: 409 });
  await dialog2.getByRole("button", { name: "Save changes" }).click();
  const rejected = await singleResponse(puts2);
  evidence.verify("S09", "session 2 stale save answered 409", rejected.status() === 409, { status: rejected.status() });

  await expect(dialog2.getByText("Your portfolio changed elsewhere")).toBeVisible();
  const draft = dialog2.getByRole("region", { name: /your draft/i });
  await expect(draft).toContainText("BTC-USD");
  await expect(draft).toContainText("0.5");
  await expect(dialog2.getByRole("checkbox")).toHaveCount(0);
  await expect(dialog2.getByRole("textbox")).toHaveCount(0);
  await expect(dialog2.getByRole("button", { name: "Save changes" })).toHaveCount(0);
  await expect(dialog2.getByRole("button", { name: "Discard & close" })).toBeVisible();
  evidence.verify("S09", "conflict panel shows the read-only draft (no silent discard)", true);
  await evidence.screenshot(page2, "S09", "conflict");

  if (run.negativeControl === "NC9") {
    await page2.evaluate(
      async ({ apiOrigin, token }) => {
        await fetch(`${apiOrigin}/api/portfolio/holdings`, {
          method: "PUT",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
          body: JSON.stringify({ expectedVersion: 0, holdings: [] }),
        }).catch(() => undefined);
      },
      { apiOrigin: run.api, token: session2.token },
    );
  }
  evidence.verify("S09", "no automatic retry from session 2", (await requestsAfterRetryWindow(page2, () => puts2.requests.length)) === 1, {
    puts: puts2.requests.length,
  });

  const after = await api.portfolio(session1);
  evidence.verify("S09", "readback holds session 1's change only, version +1", holdingsEqual(after.holdings, CERT_B_AFTER_S09) && after.version === before.version + 1, {
    version: after.version,
    holdings: normalizeHoldings(after.holdings),
  });

  // "Reload latest & start over" invalidates the portfolio query and closes the picker;
  // the user then reopens Edit Holdings, which seeds from the refetched state.
  const reads2 = captureCalls(second.context, "GET", "/api/portfolio");
  await dialog2.getByRole("button", { name: "Reload latest & start over" }).click();
  await expect(dialog2).toBeHidden();
  const refetched = await expect
    .poll(() => reads2.requests.some((request) => reads2.responses.get(request)?.status() === 200), { timeout: 20_000 })
    .toBe(true)
    .then(() => true)
    .catch(() => false);
  evidence.verify("S09", "reload closes the picker and refetches the portfolio", refetched, { reads: reads2.requests.length });
  const reopened = await openPicker(page2);
  const reopenedTsla = normalizeDecimal(await quantityBox(reopened, "TSLA").inputValue());
  const reopenedBtc = normalizeDecimal(await quantityBox(reopened, "BTC-USD").inputValue());
  evidence.verify("S09", "reopening after reload starts from the latest persisted state", reopenedTsla === "7" && reopenedBtc === "0.35", {
    tsla: reopenedTsla,
    btc: reopenedBtc,
  });
  await page2.keyboard.press("Escape");
  await expect(reopened).toBeHidden();
  evidence.verify("S09", "still exactly one PUT from session 2", puts2.requests.length === 1);

  const certAAfter = await api.portfolio(certA);
  evidence.verify("S09", "no cross-user effect on CERT_A", certAAfter.version === certABefore.version && holdingsEqual(certAAfter.holdings, certABefore.holdings));
  first.monitor.assertClean("S09");
  second.monitor.assertClean("S09");
});

// ── S10: isolation ───────────────────────────────────────────────────────────

test("S10 user and session isolation", async ({ browser }) => {
  const certA = await certSession("CERT_A");
  const certB = await certSession("CERT_B");
  await ensure(certA, CERT_A_BASELINE);
  const readA = await api.portfolio(certA);
  const readB = await api.portfolio(certB);
  const tickersA = tickersOf(readA.holdings);
  const tickersB = tickersOf(readB.holdings);
  evidence.verify("S10", "precondition: the two portfolios share no ticker", tickersA.every((t) => !tickersB.includes(t)));

  let routeHookB: RouteHook | undefined;
  if (run.negativeControl === "NC3") {
    // A server-side leak that passes the frontend's own identity check: CERT_A's holdings
    // labelled with CERT_B's user id. (A body carrying CERT_A's id is rejected by the app.)
    const rawA = (await (await fetch(`${run.api}/api/portfolio`, { headers: { Authorization: `Bearer ${certA.token}` } })).json()) as Array<Record<string, unknown>>;
    const leaked = rawA.map((row) => ({ ...row, userId: certB.userId }));
    routeHookB = async (route, request) => {
      if (!isApi(request, "GET", "/api/portfolio")) return false;
      await fulfillJson(route, 200, leaked);
      return true;
    };
  }
  const a = await monitoredContext(browser, "CERT_A");
  const b = await monitoredContext(browser, "CERT_B", { routeHook: routeHookB });
  const pageA = await a.context.newPage();
  const pageB = await b.context.newPage();
  await signInByStorage(pageA, certA, "/portfolio");
  await signInByStorage(pageB, certB, "/portfolio");
  await waitForPortfolioPage(pageA);
  await waitForPortfolioPage(pageB);
  await expect.poll(() => portfolioTableTickers(pageA)).toEqual(tickersA);
  const shownB = await expect.poll(() => portfolioTableTickers(pageB)).toEqual(tickersB).then(() => true).catch(() => false);
  evidence.verify("S10", "concurrent Portfolio pages show only each user's own holdings", shownB && (await portfolioTableTickers(pageA)).every((t) => !tickersB.includes(t)), {
    shownA: await portfolioTableTickers(pageA),
    shownB: await portfolioTableTickers(pageB),
  });
  await pageA.getByRole("link", { name: "Market Data", exact: true }).click();
  await pageB.getByRole("link", { name: "Market Data", exact: true }).click();
  const marketA = await expect.poll(() => marketTableTickers(pageA)).toEqual(tickersA).then(() => true).catch(() => false);
  const marketB = await expect.poll(() => marketTableTickers(pageB)).toEqual(tickersB).then(() => true).catch(() => false);
  evidence.verify("S10", "concurrent Market Data pages show only each user's own tickers", marketA && marketB);

  const spoofed = await api.portfolio(certA, { "X-User-Id": certB.userId });
  evidence.verify("S10", "a spoofed X-User-Id header is ignored in favour of the token", spoofed.userId === certA.userId && holdingsEqual(spoofed.holdings, readA.holdings));

  // Same-tab switch: CERT_A signs out, FRESH signs in; no CERT_A ticker may ever show in FRESH's session.
  const s = await monitoredContext(browser, "SWITCH");
  const leaks = await installLeakObserver(s.context, tickersA, certA.userId);
  const pageS = await s.context.newPage();
  await signInByStorage(pageS, certA, "/portfolio");
  await waitForPortfolioPage(pageS);
  await expect.poll(() => portfolioTableTickers(pageS)).toEqual(tickersA);
  await pageS.getByRole("button", { name: "User menu" }).click();
  await pageS.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(pageS).toHaveURL(/\/login$/);
  const email = pageS.getByLabel("Email", { exact: true });
  await email.fill(run.fresh.email);
  await pageS.getByLabel("Password", { exact: true }).fill(run.fresh.password);
  evidence.verify("S10", "email field holds FRESH's email before submit", (await email.inputValue()) === run.fresh.email);
  await pageS.getByRole("button", { name: "Sign in" }).click();
  await expect(pageS).toHaveURL(/\/overview$/, { timeout: AUTH_WAIT_MS });
  rememberFresh(await storedSession(pageS));
  await pageS.getByRole("link", { name: "Portfolio", exact: true }).click();
  await expect.poll(() => portfolioTableTickers(pageS)).toEqual(tickersOf(FRESH_TARGET));
  if (run.negativeControl === "NC8") {
    await pageS.evaluate((ticker) => {
      const node = document.createElement("div");
      node.textContent = ticker;
      document.querySelector("main")?.appendChild(node);
    }, tickersA[0]);
    await pageS.waitForTimeout(500);
  }
  await pageS.getByRole("link", { name: "Market Data", exact: true }).click();
  await expect.poll(() => marketTableTickers(pageS)).toEqual(tickersOf(FRESH_TARGET));
  evidence.verify("S10", "the leak observer ran in every document", leaks.heartbeats.length >= 2, { heartbeats: leaks.heartbeats.length });
  evidence.verify("S10", "no CERT_A ticker appeared at any time in FRESH's session", leaks.leaks.length === 0, { leaks: leaks.leaks });
  await evidence.screenshot(pageS, "S10", "fresh-after-switch");
  a.monitor.assertClean("S10");
  b.monitor.assertClean("S10");
  s.monitor.assertClean("S10");
});

// ── S11: pages, navigation, presentation ─────────────────────────────────────

test("S11 Overview, Portfolio, Market Data, AI Insights and navigation for every user", async ({ browser }, testInfo) => {
  test.setTimeout(1_200_000);
  const roles: Array<{ role: Role; session: AuthSession }> = [
    { role: "CERT_A", session: await certSession("CERT_A") },
    { role: "CERT_B", session: await certSession("CERT_B") },
    { role: "FRESH", session: await freshSession() },
  ];
  // NC10 and NC11 raise FRESH's analytics total by 1000. NC11 models a persistent divergence,
  // so it must also reach the Node re-reads that follow a disagreement.
  const injectsDivergence = (role: Role) => (run.negativeControl === "NC10" || run.negativeControl === "NC11") && role === "FRESH";
  const readAnalytics = async (role: Role, session: AuthSession) => {
    const body = await api.analytics(session);
    return run.negativeControl === "NC11" && role === "FRESH" ? { ...body, totalValue: body.totalValue + 1000 } : body;
  };
  for (const { role, session } of roles) {
    const readback = await api.portfolio(session);
    const independentSummary = await api.summary(session);
    let partialValuation = independentSummary.partialValuation;
    if (run.negativeControl === "NC11" && role === "FRESH") {
      // An unchanged PUT returns 200 and is logged as a write (the server treats it as a no-op
      // and keeps the version), which opens the suite's cache window. The injected divergence is
      // then first classified as possibly the known cache defect; only the post-expiry re-read
      // can expose it.
      const put = await api.putHoldings(session, readback.version, readback.holdings);
      if (put.status !== 200) throw new Error(`NC11 setup PUT returned HTTP ${put.status}`);
      recordHoldingsWrite(session.userId);
    }

    for (const viewport of DESKTOP_VIEWPORTS) {
      const tag = `${role}-${viewport.width}`;
      let routeHook: RouteHook | undefined;
      if (injectsDivergence(role)) {
        // NC10 must not be excused by the cache window: let every pre-write entry expire first.
        if (run.negativeControl === "NC10") await waitOutStaleAnalytics(session.userId);
        routeHook = async (route, request) => {
          if (!isApi(request, "GET", "/api/portfolio/analytics")) return false;
          const real = await route.fetch();
          const body = (await real.json()) as { totalValue: number };
          await route.fulfill({ response: real, json: { ...body, totalValue: body.totalValue + 1000 } });
          return true;
        };
      }
      const monitored = await monitoredContext(browser, role, { viewport, routeHook });
      const { context, monitor } = monitored;
      const page = await context.newPage();
      // Separate reads can differ (the 30 s analytics cache after a write, F9; or a price or FX
      // refresh), so presentation oracles compare the UI with the payload this page itself
      // rendered; persisted state is checked separately.
      const summaryCalls = captureCalls(context, "GET", "/api/portfolio/summary");
      const analyticsCalls = captureCalls(context, "GET", "/api/portfolio/analytics");
      const marketSummaryCalls = captureCalls(context, "GET", "/api/insights/market-summary");
      await signInByStorage(page, session, "/overview");

      // Overview
      const total = page.getByTestId("total-value");
      await expect(total).toBeVisible({ timeout: 45_000 });
      await expect(page.getByText(/^\$[\d,]+\.\d{2} total$/).or(page.getByText("No allocation data available."))).toBeVisible({ timeout: 45_000 });
      const totalRead = await readAgainstLatest<SummaryReadback, number | null>(page, summaryCalls, async () =>
        parseDisplayedMoney((await total.textContent()) ?? ""),
      );
      const pageSummary = totalRead.data;
      if (pageSummary.partialValuation) partialValuation = true;
      if (Math.abs(pageSummary.totalValue - independentSummary.totalValue) > MONEY_TOLERANCE) {
        evidence.observe("S11", "prices moved between the independent read and the page's own read", {
          tag,
          independent: independentSummary.totalValue,
          page: pageSummary.totalValue,
        });
      }
      evidence.verify("S11", `${tag}: Overview total equals the summary total to the cent`, totalRead.rendered !== null && Math.abs(totalRead.rendered - pageSummary.totalValue) <= MONEY_TOLERANCE, {
        shown: totalRead.rendered,
        api: pageSummary.totalValue,
      });
      const overviewRead = await readAgainstLatest<AnalyticsReadback, { shown24h: number | null; legendPercents: number[]; partialShown: boolean; allocationTotal: number | null }>(page, analyticsCalls, async () => ({
        allocationTotal: (await page.getByText(/^\$[\d,]+\.\d{2} total$/).count()) === 1
          ? parseDisplayedMoney(((await page.getByText(/^\$[\d,]+\.\d{2} total$/).textContent()) ?? "").replace(/ total$/, ""))
          : null,
        shown24h: parseDisplayedMoney(((await page.getByTestId("24h-pnl").textContent()) ?? "").trim()),
        legendPercents: (await page.locator("main li").filter({ hasText: /\d+\.\d%\s*$/ }).allTextContents()).map((t) =>
          Number(/(\d+\.\d)%\s*$/.exec(t)?.[1] ?? "NaN"),
        ),
        partialShown: await page.getByText(/^Partial \(\d+\/\d+ holdings\)$/).isVisible().catch(() => false),
      }));
      const overviewAnalytics = overviewRead.data;
      const { shown24h, legendPercents, partialShown, allocationTotal } = overviewRead.rendered;
      // The Overview shows the summary total (uncached) beside analytics-driven cards. Analytics
      // is cached per user for 30 s and no holdings write evicts it (finding F9, D10), so right
      // after a write the two can disagree. A write inside the TTL only makes that the possible
      // cause (every local full run has read CERT_B within 30 s of S09's save), so the known defect is
      // recorded only once analytics, re-read after every pre-write entry has expired, converges
      // on the summary total the page showed. Any other disagreement is a real divergence.
      const totalsAgree = Math.abs(pageSummary.totalValue - overviewAnalytics.totalValue) <= MONEY_TOLERANCE;
      if (totalsAgree) {
        evidence.record("S11", `${tag}: summary and analytics endpoints agree on the total`, true, { total: pageSummary.totalValue });
      } else {
        const analyticsReadAt = analyticsCalls.startedAt.get(overviewRead.response.request()) ?? Date.now();
        const writes = holdingsWriteTimes(session.userId);
        const [nodeSummary, nodeAnalytics] = await Promise.all([api.summary(session), readAnalytics(role, session)]);
        const detail = {
          tag,
          summary: pageSummary.totalValue,
          analytics: overviewAnalytics.totalValue,
          msSinceLastWrite: msSinceLastWriteBefore(writes, analyticsReadAt),
          nodeRereadSummary: nodeSummary.totalValue,
          nodeRereadAnalytics: nodeAnalytics.totalValue,
        };
        if (withinAnalyticsCacheWindow(writes, analyticsReadAt)) {
          const waitedMs = await waitOutStaleAnalytics(session.userId, analyticsReadAt);
          const [afterSummary, afterAnalytics] = await Promise.all([api.summary(session), readAnalytics(role, session)]);
          const converged =
            Math.abs(afterAnalytics.totalValue - afterSummary.totalValue) <= MONEY_TOLERANCE &&
            Math.abs(afterSummary.totalValue - pageSummary.totalValue) <= MONEY_TOLERANCE;
          const expiryDetail = { ...detail, waitedMs, afterExpirySummary: afterSummary.totalValue, afterExpiryAnalytics: afterAnalytics.totalValue };
          evidence.verify("S11", `${tag}: after the cache TTL, analytics converges on the summary total`, converged, expiryDetail);
          if (converged) {
            evidence.observe("S11", "Overview shows two different totals: analytics cache stale after a holdings write", expiryDetail);
            testInfo.annotations.push({ type: "expected-defect", description: "analytics-cache-stale-after-holdings-write" });
          }
        } else {
          evidence.verify("S11", `${tag}: summary and analytics endpoints agree on the total`, false, detail);
        }
      }
      if (overviewAnalytics.holdings.length > 0 && overviewAnalytics.totalValue > 0) {
        evidence.verify("S11", `${tag}: allocation card total equals the analytics total`, allocationTotal !== null && Math.abs(allocationTotal - overviewAnalytics.totalValue) <= MONEY_TOLERANCE, {
          shown: allocationTotal,
          analytics: overviewAnalytics.totalValue,
        });
      }
      const withChange = overviewAnalytics.holdings.filter((h) => h.change24hAbsolute !== null);
      const expected24h = withChange.length === 0 ? null : withChange.reduce((sum, h) => sum + (h.change24hAbsolute ?? 0), 0);
      evidence.verify("S11", `${tag}: Overview 24h card equals the analytics sum`, expected24h === null ? shown24h === null : shown24h !== null && Math.abs(shown24h - expected24h) <= MONEY_TOLERANCE, {
        shown: shown24h,
        expected: expected24h,
      });
      // Only valued holdings get a slice: an unpriced holding is excluded from the allocation,
      // as it is from the analytics total (finding F1).
      const valued = overviewAnalytics.holdings.filter((h) => h.currentValueBase != null);
      const classes = new Set(valued.map((h) => h.displayAssetClass ?? "OTHER"));
      if (valued.length > 0) {
        evidence.verify("S11", `${tag}: allocation legend has one slice per asset class`, legendPercents.length === classes.size, {
          slices: legendPercents.length,
          classes: classes.size,
        });
        if (overviewAnalytics.totalValue > 0) {
          const sum = legendPercents.reduce((acc, p) => acc + p, 0);
          evidence.verify("S11", `${tag}: allocation percentages sum to 100`, Math.abs(sum - 100) <= 0.05 * legendPercents.length + 0.01, { sum });
        }
      } else if (overviewAnalytics.holdings.length > 0) {
        evidence.verify("S11", `${tag}: allocation shows no slice when no holding has a value`, legendPercents.length === 0 && allocationTotal === null, {
          slices: legendPercents.length,
          allocationTotal,
        });
      }
      const coverage = overviewAnalytics.performanceCoverage;
      const expectPartial = Boolean(coverage?.partial) && (coverage?.totalHoldings ?? 0) > 0;
      evidence.verify("S11", `${tag}: performance-coverage label shown iff coverage is partial`, partialShown === expectPartial, { expectPartial, partialShown });
      evidence.verify("S11", `${tag}: Overview has no horizontal overflow`, await hasNoHorizontalOverflow(page));
      await evidence.screenshot(page, "S11", `${tag}-overview`);

      // Portfolio
      await page.getByRole("link", { name: "Portfolio", exact: true }).click();
      await expect(page).toHaveURL(/\/portfolio$/);
      const expectedTickers = tickersOf(readback.holdings);
      if (expectedTickers.length > 0) await expect.poll(() => portfolioTableTickers(page), { timeout: 45_000 }).toEqual(expectedTickers);
      evidence.verify("S11", `${tag}: Portfolio rows equal the readback tickers`, JSON.stringify(await portfolioTableTickers(page)) === JSON.stringify(expectedTickers));
      const strip = page.getByText(/^Prices as of/);
      if (readback.holdings.length > 0) {
        await expect(strip).toBeVisible();
        const stripRead = await readAgainstLatest<SummaryReadback, string>(page, summaryCalls, async () =>
          ((await strip.textContent()) ?? "").replace(/\s+/g, " "),
        );
        const freshness = stripRead.data.assetPriceFreshness;
        const affected = { FRESH: 0, MISSING: freshness.missingPriceHoldings, UNKNOWN: freshness.unknownPriceHoldings, STALE: freshness.staleHoldings }[freshness.state];
        const expectedSummary = freshness.state === "FRESH" ? "All prices fresh" : `${affected} holding${affected === 1 ? "" : "s"} ${freshness.state.toLowerCase()}`;
        const stripText = stripRead.rendered;
        evidence.verify("S11", `${tag}: freshness strip states the API freshness`, stripText.endsWith(`— ${expectedSummary}`), { stripText, expectedSummary });
        await page.getByRole("button", { name: "Details" }).click();
        const popover = page.getByRole("dialog", { name: "Price freshness details" });
        await expect(popover).toBeVisible();
        const expectedTimestamp = await page.evaluate(
          (iso) => (iso ? new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso)) : "No price observation on record"),
          freshness.oldestKnownAssetPriceObservationTimestamp ?? null,
        );
        const popoverText = ((await popover.textContent()) ?? "").replace(/\s+/g, " ");
        const expectedRows = [
          freshness.missingPriceHoldings > 0 ? `Missing: ${freshness.missingPriceHoldings}` : null,
          freshness.unknownPriceHoldings > 0 ? `Unknown: ${freshness.unknownPriceHoldings}` : null,
          freshness.staleHoldings > 0 ? `Stale: ${freshness.staleHoldings}` : null,
        ].filter((row): row is string => row !== null);
        evidence.verify("S11", `${tag}: freshness details show the API timestamp and counts`, popoverText.includes(expectedTimestamp) && (expectedRows.length === 0 ? popoverText.includes("All prices fresh") : expectedRows.every((row) => popoverText.includes(row))), {
          expectedTimestamp,
          expectedRows,
        });
        await page.keyboard.press("Escape");
      }
      const portfolioRead = await readAgainstLatest<AnalyticsReadback, Map<string, string[]>>(page, analyticsCalls, () =>
        rowCellsByTicker(page, "td:first-child span.font-mono"),
      );
      const portfolioByTicker = new Map(portfolioRead.data.holdings.map((h) => [h.ticker, h]));
      const portfolio24h = new Map<string, number | null>();
      for (const [ticker, cells] of portfolioRead.rendered) {
        const shown = parseDisplayedPercent(cells[5] ?? "");
        portfolio24h.set(ticker, shown);
        const h = portfolioByTicker.get(ticker);
        const expected = h && h.change24hPercent !== null && h.change24hAbsolute !== null ? h.change24hPercent : null;
        evidence.verify("S11", `${tag}: Portfolio 24h for ${ticker} matches analytics`, expected === null ? shown === null : shown !== null && Math.abs(shown - expected) <= PERCENT_TOLERANCE, {
          shown,
          expected,
        });
      }
      evidence.verify("S11", `${tag}: Portfolio has no horizontal overflow`, await hasNoHorizontalOverflow(page));
      await evidence.screenshot(page, "S11", `${tag}-portfolio`);

      // Market Data
      await page.getByRole("link", { name: "Market Data", exact: true }).click();
      await expect(page).toHaveURL(/\/market-data$/);
      if (expectedTickers.length > 0) {
        await expect.poll(() => marketTableTickers(page), { timeout: 45_000 }).toEqual(expectedTickers);
        await expect(page.getByTestId("change24h-loading")).toHaveCount(0, { timeout: 30_000 });
        const marketRead = await readAgainstLatest<AnalyticsReadback, Map<string, string[]>>(page, analyticsCalls, () =>
          rowCellsByTicker(page, "td:first-child"),
        );
        const marketByTicker = new Map(marketRead.data.holdings.map((h) => [h.ticker, h]));
        for (const [ticker, cells] of marketRead.rendered) {
          const shown = parseDisplayedPercent(cells[2] ?? "");
          const expected = marketByTicker.get(ticker)?.change24hPercent ?? null;
          evidence.verify("S11", `${tag}: Market Data 24h for ${ticker} matches analytics`, expected === null ? shown === null : shown !== null && Math.abs(shown - expected) <= PERCENT_TOLERANCE, {
            shown,
            expected,
          });
          evidence.verify("S11", `${tag}: 24h for ${ticker} agrees across Portfolio and Market Data`, portfolio24h.get(ticker) === shown, {
            portfolio: portfolio24h.get(ticker) ?? null,
            marketData: shown,
          });
        }
      } else {
        await expect(page.getByText("No market data available.", { exact: true })).toBeVisible();
      }
      evidence.verify("S11", `${tag}: Market Data has no horizontal overflow`, await hasNoHorizontalOverflow(page));
      await evidence.screenshot(page, "S11", `${tag}-market-data`);

      // AI Insights
      await page.getByRole("link", { name: "AI Insights", exact: true }).click();
      await expect(page).toHaveURL(/\/ai-insights$/);
      await expect(page.getByTestId("market-summary-grid")).toBeVisible({ timeout: 45_000 });
      const gridRead = await readAgainstLatest<Record<string, { ticker?: unknown } | null>, string[]>(page, marketSummaryCalls, async () =>
        (await page.getByTestId("market-summary-grid").locator(".font-mono").allTextContents()).map((t) => t.trim()).sort(),
      );
      const payloadTickers = Object.values(gridRead.data)
        .filter((entry): entry is { ticker: string } => entry !== null && typeof entry?.ticker === "string")
        .map((entry) => entry.ticker)
        .sort();
      evidence.verify("S11", `${tag}: AI Insights cards equal the page's market-summary tickers`, payloadTickers.length > 0 && JSON.stringify(gridRead.rendered) === JSON.stringify(payloadTickers), {
        cards: gridRead.rendered.length,
        payload: payloadTickers.length,
      });
      evidence.verify("S11", `${tag}: AI Insights has no horizontal overflow`, await hasNoHorizontalOverflow(page));
      await evidence.screenshot(page, "S11", `${tag}-ai-insights`);

      if (viewport.width === 1440) {
        for (const [label, href] of [
          ["Overview", "/overview"],
          ["Portfolio", "/portfolio"],
          ["Market Data", "/market-data"],
          ["Settings", "/settings"],
          ["AI Insights", "/ai-insights"],
        ] as const) {
          await page.getByRole("link", { name: label, exact: true }).click();
          await expect(page).toHaveURL(new RegExp(`${href}$`));
        }
        await expect(page.getByTestId("market-summary-grid")).toBeVisible({ timeout: 45_000 });
        evidence.verify("S11", `${tag}: every sidebar link navigates to its route`, true);
      }
      monitor.assertClean("S11");
      await finalizeContext(monitored, testInfo);
    }
    if (partialValuation) {
      // The API excludes unvalued holdings (e.g. no FX rate) from the total, and no component
      // reads partialValuation. Recorded as the expected defect partial-valuation-not-presented
      // (owner decision D9); a fix must replace this with an assertion on its own presentation.
      evidence.observe("S11", "partial valuation presentation", { role, partialValuation: true, signalled: false });
      testInfo.annotations.push({ type: "expected-defect", description: "partial-valuation-not-presented" });
    }
  }
});

// ── S12: chat ────────────────────────────────────────────────────────────────

test("S12 AI Insights chat answers one question", async ({ browser }) => {
  test.skip(run.skipChat, "D4: chat disabled for this run; recorded as UNRUN");
  const certA = await certSession("CERT_A");
  const { context, monitor } = await monitoredContext(browser, "CERT_A");
  const page = await context.newPage();
  const chats = captureCalls(context, "POST", "/api/chat");
  await signInByStorage(page, certA, "/ai-insights");
  await page.getByTestId("chat-input").fill("How is AAPL doing?");
  await page.getByTestId("chat-send").click();
  const response = await singleResponse(chats);
  const body = (await response.json()) as { response?: unknown };
  const answer = typeof body.response === "string" ? body.response.trim() : "";
  evidence.verify("S12", "exactly one chat request answered 200 with a non-empty reply", response.status() === 200 && answer.length > 0, {
    status: response.status(),
    replyLength: answer.length,
  });
  await expect(page.getByTestId("chat-messages")).toContainText(answer.slice(0, 60));
  evidence.verify("S12", "the reply is rendered in the transcript", true);
  evidence.verify("S12", "no rate-limit countdown", (await page.getByTestId("chat-rate-limit-countdown").count()) === 0);
  evidence.observe("S12", "limitation", {
    note: run.mode === "local" ? "local insight-service uses deterministic mocks; the LLM path is not exercised" : "reply content cannot distinguish the LLM path from the deterministic fallback",
  });
  await evidence.screenshot(page, "S12", "chat");
  monitor.assertClean("S12");
});

// ── S13: non-demo reset control ──────────────────────────────────────────────

test("S13 non-demo users are not offered the demo reset", async ({ browser }, testInfo) => {
  const certA = await certSession("CERT_A");
  const { context, monitor } = await monitoredContext(browser, "CERT_A");
  const page = await context.newPage();
  await signInByStorage(page, certA, "/portfolio");
  await waitForPortfolioPage(page);
  const visible = (await page.getByRole("button", { name: "Reset Demo Portfolio" }).count()) > 0;
  evidence.observe("S13", "reset control visible to a non-demo user", {
    visible,
    expectation: "hidden (defect unless owner decision D5 accepts it)",
    caveat: visible
      ? null
      : "not visible: either the defect is fixed or NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL is off in this build; confirm the repository variable before reading this as fixed",
  });
  if (visible) {
    // Recorded as an expected defect (never clicked: the gateway answers 403 for non-demo users).
    testInfo.annotations.push({ type: "expected-defect", description: "non-demo-reset-control-visible" });
  }
  await evidence.screenshot(page, "S13", "portfolio");
  monitor.assertClean("S13");
});

// ── S99: final state ─────────────────────────────────────────────────────────

test("S99 restore declared baselines and record final state", async () => {
  const finalState: Array<Record<string, unknown>> = [];
  for (const role of ["CERT_A", "CERT_B"] as const) {
    const session = await certSession(role);
    const result = await ensure(session, BASELINES[role]);
    const restored = holdingsEqual(result.after.holdings, BASELINES[role]);
    evidence.verify("S99", `${role} restored to its declared baseline`, restored, { outcome: result.outcome, version: result.after.version });
    finalState.push({ role, version: result.after.version, holdings: normalizeHoldings(result.after.holdings), restoration: result.outcome === "written" ? "restored" : "not_needed" });
  }
  const fresh = await freshSession().catch(() => null);
  if (fresh) {
    const readback = await api.portfolio(fresh);
    finalState.push({ role: "FRESH", retained: true, version: readback.version, holdings: normalizeHoldings(readback.holdings) });
  } else {
    finalState.push({ role: "FRESH", retained: false, note: "FRESH was not created" });
  }
  evidence.writeJson("final-state.json", { runId: run.runId, recordedAt: new Date().toISOString(), users: finalState });
  evidence.writeJson("restoration.json", { status: "confirmed", by: "S99" });

  const authLog = readFileSync(AUTH_LOG, "utf8").trim().split("\n").filter(Boolean).map((line) => JSON.parse(line) as { source: string });
  evidence.observe("S99", "auth requests this run", {
    total: authLog.length,
    api: authLog.filter((l) => l.source === "api").length,
    browser: authLog.filter((l) => l.source === "browser").length,
  });

  if (run.negativeControl === "NC7") {
    writeFileSync(path.join(RUN_DIR, "nc7-canary.json"), JSON.stringify({ leaked: run.fresh.password }));
  }
  const hits = scanArtifactsForSecrets(RUN_DIR, secretValues);
  evidence.verify("S99", "no secret appears in any shareable artifact", hits.length === 0, { files: hits.map((h) => h.file) });
});
