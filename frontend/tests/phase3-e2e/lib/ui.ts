/**
 * Browser helpers for the Phase 3 scenarios. Selectors are the real accessible names
 * and texts from the components (see the design, section 5).
 */
import { expect, type BrowserContext, type Locator, type Page, type Request, type Response } from "@playwright/test";
import type { AuthSession } from "./api";

export const AUTH_STORAGE_KEY = "wmpt.auth.session";

/** Loads a static page, stores the session once (no init script, so sign-out stays possible), then navigates. */
export async function signInByStorage(page: Page, session: AuthSession, target: string): Promise<void> {
  await page.goto("/login", { waitUntil: "domcontentloaded" });
  await page.evaluate(
    ({ key, value }) => window.localStorage.setItem(key, JSON.stringify(value)),
    { key: AUTH_STORAGE_KEY, value: session },
  );
  await page.goto(target, { waitUntil: "domcontentloaded" });
}

export async function storedSession(page: Page): Promise<AuthSession | null> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), AUTH_STORAGE_KEY);
  return raw ? (JSON.parse(raw) as AuthSession) : null;
}

export interface CapturedCalls {
  readonly requests: Request[];
  readonly responses: Map<Request, Response>;
  /** Epoch ms at which each request started (the server-side read happens right after). */
  readonly startedAt: Map<Request, number>;
}

/** Counts request starts synchronously, so a delayed or failed retry cannot hide. */
export function captureCalls(context: BrowserContext, method: string, pathname: string): CapturedCalls {
  const requests: Request[] = [];
  const responses = new Map<Request, Response>();
  const startedAt = new Map<Request, number>();
  const matches = (request: Request) => request.method() === method && new URL(request.url()).pathname === pathname;
  context.on("request", (request) => {
    if (!matches(request)) return;
    requests.push(request);
    startedAt.set(request, Date.now());
  });
  context.on("response", (response) => {
    if (matches(response.request())) responses.set(response.request(), response);
  });
  return { requests, responses, startedAt };
}

/** Waits for exactly one matching request and its response. Auth waits pass a longer timeout (the pacer holds). */
export async function singleResponse(calls: CapturedCalls, timeout = 20_000): Promise<Response> {
  await expect.poll(() => calls.requests.length, { timeout }).toBe(1);
  const request = calls.requests[0];
  await expect.poll(() => calls.responses.has(request), { timeout }).toBe(true);
  return calls.responses.get(request)!;
}

/** The latest HTTP 200 response of a capture, or null when none has arrived yet. */
export function latestOk(calls: CapturedCalls): Response | null {
  const ok = calls.requests.filter((request) => calls.responses.get(request)?.status() === 200);
  return ok.length === 0 ? null : calls.responses.get(ok[ok.length - 1])!;
}

/**
 * Reads the DOM against the data the page itself received: waits for a 200 response,
 * runs `read`, and repeats if a newer response (a refetch) arrived meanwhile, so the
 * expectation and the rendered value come from the same payload. Returns that response
 * too, so callers can time the exact read they compared.
 */
export async function readAgainstLatest<T, R>(page: Page, calls: CapturedCalls, read: () => Promise<R>): Promise<{ data: T; rendered: R; response: Response }> {
  await expect.poll(() => latestOk(calls) !== null, { timeout: 30_000 }).toBe(true);
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const before = latestOk(calls)!;
    // Node sees the response at headers time; let the page consume the body and re-render first.
    await before.finished();
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const rendered = await read();
    if (latestOk(calls) === before) return { data: (await before.json()) as T, rendered, response: before };
  }
  throw new Error("the page kept refetching while being read");
}

/**
 * Advances the page clock past TanStack Query's 30-second retry-delay cap without a
 * wall-clock sleep, then returns the request count. Requires page.clock.install()
 * before the first navigation.
 */
export async function requestsAfterRetryWindow(page: Page, count: () => number): Promise<number> {
  const now = await page.evaluate(() => Date.now());
  await page.clock.pauseAt(now + 1_000);
  try {
    await page.clock.runFor(31_000);
  } finally {
    await page.clock.resume();
  }
  return count();
}

export async function waitForPortfolioPage(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/portfolio$/);
  await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({ timeout: 45_000 });
  await expect(page.getByRole("button", { name: "Edit Holdings" })).toBeVisible({ timeout: 45_000 });
}

export async function openPicker(page: Page): Promise<Locator> {
  await page.getByRole("button", { name: "Edit Holdings" }).click();
  const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("group", { name: "Assets" })).toBeVisible({ timeout: 30_000 });
  return dialog;
}

export function quantityBox(dialog: Locator, ticker: string): Locator {
  return dialog.getByRole("textbox", { name: `${ticker} quantity`, exact: true });
}

/** Searches the catalog, selects the asset and enters its quantity. */
export async function addAsset(dialog: Locator, ticker: string, quantity: string): Promise<void> {
  const search = dialog.getByRole("searchbox", { name: "Search assets by ticker or name" });
  await search.fill(ticker);
  const checkbox = dialog.getByRole("checkbox", { name: `Select ${ticker}`, exact: true });
  await expect(checkbox).toHaveAttribute("aria-checked", "false");
  await checkbox.click();
  await expect(checkbox).toHaveAttribute("aria-checked", "true");
  await quantityBox(dialog, ticker).fill(quantity);
  await search.fill("");
}

export async function removeAsset(dialog: Locator, ticker: string): Promise<void> {
  const checkbox = dialog.getByRole("checkbox", { name: `Select ${ticker}`, exact: true });
  await expect(checkbox).toHaveAttribute("aria-checked", "true");
  await checkbox.click();
  await expect(checkbox).toHaveAttribute("aria-checked", "false");
}

/** Reads the Review step's section counts ("Added — 2" etc.); absent sections are 0. */
export async function reviewCounts(dialog: Locator): Promise<Record<"Added" | "Changed" | "Removed" | "Unchanged", number>> {
  await expect(dialog.getByRole("button", { name: "Save changes" })).toBeVisible();
  const result = { Added: 0, Changed: 0, Removed: 0, Unchanged: 0 };
  for (const label of Object.keys(result) as Array<keyof typeof result>) {
    const heading = dialog.getByText(new RegExp(`^${label} — \\d+$`));
    if ((await heading.count()) === 1) {
      const text = (await heading.textContent()) ?? "";
      result[label] = Number(text.split("—")[1].trim());
    }
  }
  return result;
}

/** Ticker cells of the Portfolio holdings table. */
export async function portfolioTableTickers(page: Page): Promise<string[]> {
  const cells = page.locator("main table tbody tr td:first-child span.font-mono");
  return (await cells.allTextContents()).map((t) => t.trim()).sort();
}

/** Ticker badges of the Market Data table. */
export async function marketTableTickers(page: Page): Promise<string[]> {
  const cells = page.locator("main table tbody tr td:first-child");
  return (await cells.allTextContents()).map((t) => t.trim()).sort();
}

/** Row text by ticker for a table whose first cell holds the ticker. */
export async function rowCellsByTicker(page: Page, tickerSelector: string): Promise<Map<string, string[]>> {
  const rows = page.locator("main table tbody tr");
  const out = new Map<string, string[]>();
  for (let i = 0; i < (await rows.count()); i += 1) {
    const row = rows.nth(i);
    const ticker = ((await row.locator(tickerSelector).first().textContent()) ?? "").trim();
    const cells = (await row.locator("td").allTextContents()).map((t) => t.replace(/\s+/g, " ").trim());
    if (ticker) out.set(ticker, cells);
  }
  return out;
}

/**
 * D11: the Portfolio footer's 24h total as displayed ("—" when unavailable). `labels` counts the
 * matching labels, so a missing or duplicated footer is reported rather than read as "no total".
 */
export async function readFooter24h(page: Page): Promise<{ labels: number; text: string | null }> {
  const label = page.locator("main p").filter({ hasText: /^24h$/ });
  const labels = await label.count();
  if (labels !== 1) return { labels, text: null };
  return { labels, text: ((await label.locator("xpath=following-sibling::p[1]").textContent()) ?? "").trim() };
}

const PERCENT = /([+-]?\d+(?:\.\d+)?)%/;

/** Parses the first percentage in a cell; null for the unavailable dash. */
export function parseDisplayedPercent(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "—" || trimmed === "") return null;
  const match = PERCENT.exec(trimmed);
  if (!match) throw new Error(`no percentage in ${JSON.stringify(text)}`);
  return Number(match[1]);
}

export interface LeakRecorder {
  readonly leaks: Array<{ ticker: string; path: string; region: string; snippet: string }>;
  readonly heartbeats: Array<{ path: string }>;
}

/**
 * Installs a DOM observer in every document of the context. Any forbidden ticker shown
 * in main (the user-scoped region) while a different user's session is stored is reported to Node at
 * once through a binding, so a document swap cannot erase it. Each document also sends
 * a heartbeat, so an observer gap is detectable.
 */
export async function installLeakObserver(
  context: BrowserContext,
  forbidden: readonly string[],
  ownerUserId: string,
): Promise<LeakRecorder> {
  const recorder: LeakRecorder = { leaks: [], heartbeats: [] };
  await context.exposeBinding("__p3ReportLeak", (_source, leak: { ticker: string; path: string; region: string; snippet: string }) => {
    recorder.leaks.push(leak);
  });
  await context.exposeBinding("__p3Heartbeat", (_source, beat: { path: string }) => {
    recorder.heartbeats.push(beat);
  });
  await context.addInitScript(
    ({ tickers, owner, key }) => {
      const w = window as unknown as {
        __p3ReportLeak: (leak: { ticker: string; path: string; region: string; snippet: string }) => void;
        __p3Heartbeat: (beat: { path: string }) => void;
      };
      const reported = new Set<string>();
      const check = () => {
        let uid: string | null = null;
        try {
          uid = (JSON.parse(window.localStorage.getItem(key) ?? "null") as { userId?: string } | null)?.userId ?? null;
        } catch {
          uid = null;
        }
        if (!uid || uid === owner) return;
        const path = window.location.pathname;
        if (!["/overview", "/portfolio", "/market-data"].includes(path)) return;
        // main only: the header ticker falls back to the global market summary (same for every
        // user), so a ticker name there cannot distinguish a leak from global data.
        for (const region of ["main"]) {
          const text = Array.from(document.querySelectorAll(region))
            .map((node) => node.textContent ?? "")
            .join("\n");
          for (const ticker of tickers) {
            const id = `${path}|${region}|${ticker}`;
            const at = text.indexOf(ticker);
            if (at >= 0 && !reported.has(id)) {
              reported.add(id);
              void w.__p3ReportLeak({ ticker, path, region, snippet: text.slice(Math.max(0, at - 40), at + 40) });
            }
          }
        }
      };
      const start = () => {
        void w.__p3Heartbeat({ path: window.location.pathname });
        new MutationObserver(check).observe(document.documentElement, { subtree: true, childList: true, characterData: true });
        check();
      };
      if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
      else start();
    },
    { tickers: [...forbidden], owner: ownerUserId, key: AUTH_STORAGE_KEY },
  );
  return recorder;
}

/** True when the page has no horizontal overflow at the current viewport. */
export async function hasNoHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
}
