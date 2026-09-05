/* eslint-disable react-hooks/rules-of-hooks --
 * Name-based false positives, not React — the same ones `helpers/demo-auth.ts`
 * documents. Playwright names every fixture provider's second parameter `use`,
 * which `eslint-config-next`'s repo-wide `react-hooks` rule reads as React 19's
 * `use()` hook, and `useIsolatedDemoContext` matches the custom-hook naming
 * convention. Both names are fixed by Task 9.6's published contract.
 */
/**
 * B2 Task 9.4 — real demo presence integration.
 *
 * Proves the Asset Picker's presence advisory against the *real* authenticated
 * gateway and real Redis: `AssetPicker → usePresence → GET /api/presence/demo →
 * PresenceController → DemoPresenceService → Redis`. Nothing here fulfills or
 * mocks the presence route — every boolean asserted below is read out of an
 * actual response body, and every banner assertion follows the response that
 * caused it.
 *
 * Requirements: 6.1 (two independent logins are two sessions, two tabs on one
 * token are one), 6.3 (exactly one authenticated GET per opening, no polling),
 * 6.4 (one persistent advisory banner, never blocking), and the design.md D4
 * TTL sweep. Requirement 6.5's fail-open path is deliberately NOT here: forcing
 * a Redis outage would mean breaking a service shared with the rest of this
 * stack. It is covered by injected-failure Vitest cases
 * (`AssetPicker.presence.integration.test.tsx`) and by the gateway's own
 * real-Redis `DemoPresenceIntegrationTest`.
 *
 * Attribution: presence GETs are counted from `page.on("response")`, so only
 * browser traffic is counted. Session B is driven through an `APIRequestContext`
 * instead, which never touches the page — deliberate API setup probes therefore
 * cannot inflate a per-opening count. Preflight `OPTIONS` is excluded by
 * matching on the GET method.
 *
 * LOCAL-ONLY — ignored by `playwright.config.ts`'s chromium project; run only
 * via `playwright.presence.real.config.ts`. Task 9.9 owns broader Wave 9 CI.
 *
 * ---------------------------------------------------------------------------
 * Fresh-stack setup (this config has no `globalSetup` of its own)
 *
 * From the repository root:
 *
 *   1. Bring the stack up WITH the presence overlay. The overlay shortens the
 *      gateway's presence TTL (production default is 150s), turns on
 *      portfolio-service's demo-portfolio initializer, and moves Redis's *host*
 *      port so an unrelated Redis already holding 6379 is left alone:
 *
 *        $env:INTERNAL_API_KEY = "<any non-blank throwaway local value>"
 *        docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml up -d --build
 *        docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml ps
 *
 *      Wait until api-gateway and portfolio-service report healthy.
 *
 *   2. The demo account needs no separate seeding step. Its user row comes from
 *      migration `V15__Reconcile_Auth_Seed_Users.sql`, and its portfolio is
 *      converged on portfolio-service startup by `DemoPortfolioInitializer`,
 *      which the overlay enables. Note the suite's ordinary Playwright global
 *      setup does NOT cover this: it seeds the Golden-State E2E subject
 *      (`00000000-0000-0000-0000-000000000e2e`), a different account than the
 *      demo subject this spec authenticates as. Running it is therefore
 *      optional here; it is still useful as a stack health gate:
 *
 *        cd frontend
 *        $env:INTERNAL_API_KEY = "<the same value as step 1>"
 *        $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
 *        npx ts-node --compiler-options '{"module":"commonjs"}' tests/e2e/global-setup.ts
 *
 *   3. Run this spec, from `frontend/`:
 *
 *        $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
 *        $env:DEMO_TEST_EMAIL = "demo@wealthtracker.dev"
 *        $env:DEMO_TEST_PASSWORD = "demo-wealthtracker-2026"
 *        $env:APP_DEMO_PRESENCE_TTL = "20s"   # must match the overlay's value
 *        npx playwright test --config playwright.presence.real.config.ts
 *
 * Teardown (optional), from the repository root:
 *
 *        docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml down
 *
 *   Tear down only this stack. Do not stop unrelated containers.
 * ---------------------------------------------------------------------------
 */
import { test as base, expect, request as apiRequest } from "@playwright/test";
import type { APIRequestContext, BrowserContext, Page, Response } from "@playwright/test";
import {
  authenticateDemoSession,
  useIsolatedDemoContext,
  type DemoSession,
} from "./helpers/demo-auth";

const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(
  /\/+$/,
  "",
);

/**
 * How long a session survives without being touched, mirroring the gateway's
 * `app.demo-presence.ttl`. Parsed from the same `APP_DEMO_PRESENCE_TTL` the
 * compose overlay sets so the two cannot silently drift; the default matches
 * the overlay's own default rather than production's 150s, because this spec is
 * only ever run against that overlay.
 */
function presenceTtlSeconds(): number {
  const raw = (process.env.APP_DEMO_PRESENCE_TTL ?? "").trim();
  const match = /^(\d+)s?$/.exec(raw);
  return match ? Number(match[1]) : 20;
}

const TTL_SECONDS = presenceTtlSeconds();
/** The gateway sweeps scores <= now - ttl - 1; a little slack absorbs clock granularity. */
const EXPIRY_WAIT_MS = (TTL_SECONDS + 4) * 1_000;
/** Long enough to catch a stray second request, short enough not to age a session out. */
const QUIET_WINDOW_MS = 3_000;

const BANNER_TEXT = "Another demo session is active — your changes may not save.";

type PresenceObservation = {
  status: number;
  anotherSessionActive: boolean | null;
};

function isPresenceGet(response: Response): boolean {
  if (response.request().method() !== "GET") return false;
  const url = new URL(response.url());
  return url.pathname === "/api/presence/demo" || url.pathname.endsWith("/presence/demo");
}

/**
 * Records every browser-issued presence GET, with its actual response body.
 * Assertions read the recorded booleans rather than inferring them from the UI.
 */
function recordPresenceGets(page: Page): PresenceObservation[] {
  const observations: PresenceObservation[] = [];
  page.on("response", async (response) => {
    if (!isPresenceGet(response)) return;
    let anotherSessionActive: boolean | null = null;
    if (response.ok()) {
      try {
        const body = (await response.json()) as { anotherSessionActive?: unknown };
        anotherSessionActive =
          typeof body.anotherSessionActive === "boolean" ? body.anotherSessionActive : null;
      } catch {
        anotherSessionActive = null;
      }
    }
    observations.push({ status: response.status(), anotherSessionActive });
  });
  return observations;
}

/**
 * A ticker the demo portfolio actually holds, so the draft edit below targets a
 * row that is genuinely seeded-and-checked.
 *
 * Deliberately not a positional locator: `buildBrowseRows` lists every drafted
 * ticker before the undrafted catalog, so unchecking a row reorders the list
 * underneath a `.first()` selector, and with the demo account's ~159 holdings
 * the resulting layout shift can send a click to a neighbouring row while the
 * assertion still reads the original one. Naming the ticker makes both the
 * click and the assertion refer to the same row regardless of order.
 */
async function firstHeldTicker(
  api: APIRequestContext,
  session: DemoSession,
): Promise<string> {
  const response = await api.get(`${API_BASE_URL}/api/portfolio`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(response.status(), "demo portfolio read must succeed").toBe(200);
  const body = (await response.json()) as Array<{
    holdings?: Array<{ assetTicker?: string; ticker?: string }>;
  }>;
  const ticker = (Array.isArray(body) ? body : [])
    .flatMap((portfolio) => portfolio.holdings ?? [])
    .map((holding) => holding.assetTicker ?? holding.ticker)
    .find((value): value is string => typeof value === "string" && value.length > 0);
  expect(
    ticker,
    "the demo portfolio must hold at least one asset for the draft-edit leg; is " +
      "APP_DEMO_SEED_ON_STARTUP enabled on portfolio-service?",
  ).toBeTruthy();
  return ticker as string;
}

/** A deliberate, non-browser presence call — this is how session B is kept alive. */
async function presenceProbe(
  api: APIRequestContext,
  session: DemoSession,
): Promise<boolean> {
  const response = await api.get(`${API_BASE_URL}/api/presence/demo`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(response.status(), "presence probe must reach the real gateway").toBe(200);
  const body = (await response.json()) as { anotherSessionActive: boolean };
  return body.anotherSessionActive;
}

async function openPicker(page: Page) {
  await page.getByRole("button", { name: "Edit Holdings" }).click();
  const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
  await expect(dialog).toBeVisible({ timeout: 60_000 });
  return dialog;
}

async function closePicker(page: Page) {
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "Edit Holdings" })).toBeHidden({
    timeout: 30_000,
  });
}

async function gotoPortfolio(page: Page) {
  await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
  await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
    timeout: 60_000,
  });
}

/**
 * Waits for exactly one further presence GET beyond `before`, then holds still
 * to prove no second one follows. Returns that opening's single observation.
 */
async function expectExactlyOneNewPresenceGet(
  observations: PresenceObservation[],
  before: number,
  label: string,
): Promise<PresenceObservation> {
  await expect
    .poll(() => observations.length - before, {
      timeout: 60_000,
      message: `${label}: expected the opening to issue its presence GET`,
    })
    .toBe(1);

  await new Promise((resolve) => setTimeout(resolve, QUIET_WINDOW_MS));

  expect(
    observations.length - before,
    `${label}: exactly one authenticated GET per opening (requirements.md 6.3) — ` +
      "no polling, no retry, no second request after the first resolved",
  ).toBe(1);

  return observations[before];
}

type WorkerFixtures = {
  /** The browser's demo session. One login, reused for every test in this file. */
  sessionA: DemoSession;
  /** A second, independently issued demo login — never a copy of A's state. */
  sessionB: DemoSession;
  api: APIRequestContext;
};

type TestFixtures = {
  contextA: BrowserContext;
  pageA: Page;
};

const test = base.extend<TestFixtures, WorkerFixtures>({
  api: [
    async ({}, use) => {
      const context = await apiRequest.newContext();
      try {
        await use(context);
      } finally {
        await context.dispose();
      }
    },
    { scope: "worker" },
  ],
  sessionA: [
    async ({ api }, use) => {
      await use(await authenticateDemoSession(api, { apiBaseUrl: API_BASE_URL }));
    },
    { scope: "worker" },
  ],
  sessionB: [
    async ({ api }, use) => {
      await use(await authenticateDemoSession(api, { apiBaseUrl: API_BASE_URL }));
    },
    { scope: "worker" },
  ],
  contextA: async ({ browser, baseURL, sessionA }, use) => {
    await useIsolatedDemoContext(browser, { baseURL, session: sessionA }, use);
  },
  pageA: async ({ contextA }, use) => {
    await use(await contextA.newPage());
  },
});

test.describe.serial("Asset Picker — real demo presence integration (Task 9.4)", () => {
  test.beforeAll(async ({ api, sessionA, sessionB }) => {
    // Two *independent* logins, not one storage state copied twice. Neither the
    // tokens nor any raw jti is printed; distinctness is asserted as a boolean
    // here and proved functionally by the two-session leg below.
    expect(
      sessionA.token === sessionB.token,
      "session A and session B must be independently issued logins",
    ).toBe(false);

    // Start from isolated presence state without deleting anyone's member and
    // without FLUSHALL: simply wait until A sees no other live session, which
    // can only happen once every earlier member has aged out of the ZSET. This
    // keeps the spec reproducible on a stack that has already been used.
    await expect
      .poll(() => presenceProbe(api, sessionA), {
        timeout: (TTL_SECONDS + 15) * 1_000,
        intervals: [2_000],
        message:
          "expected the demo presence set to quiesce to session A alone before the run; " +
          `is another demo session still active, or is the TTL longer than ${TTL_SECONDS}s?`,
      })
      .toBe(false);
  });

  test("a lone demo session reads false, shows no banner, and a second tab on the same token is still one session", async ({
    pageA,
    contextA,
  }) => {
    const observations = recordPresenceGets(pageA);

    await gotoPortfolio(pageA);
    expect(
      observations.length,
      "presence must not be queried while the picker is closed (requirements.md 6.3)",
    ).toBe(0);

    const dialog = await openPicker(pageA);
    const first = await expectExactlyOneNewPresenceGet(observations, 0, "lone session");

    expect(first.status).toBe(200);
    expect(
      first.anotherSessionActive,
      "a lone demo session must read false from the real endpoint",
    ).toBe(false);
    await expect(dialog.getByText(BANNER_TEXT)).toHaveCount(0);

    // Requirement 6.1 — a second tab under the SAME issued token is the same
    // session, so it must still read false rather than seeing "itself".
    const secondTab = await contextA.newPage();
    const secondTabObservations = recordPresenceGets(secondTab);
    await gotoPortfolio(secondTab);
    const secondTabDialog = await openPicker(secondTab);
    const sameTokenObservation = await expectExactlyOneNewPresenceGet(
      secondTabObservations,
      0,
      "second tab, same token",
    );

    expect(
      sameTokenObservation.anotherSessionActive,
      "two tabs sharing one issued token are one session (requirements.md 6.1)",
    ).toBe(false);
    await expect(secondTabDialog.getByText(BANNER_TEXT)).toHaveCount(0);
    await secondTab.close();
  });

  test("false → true → false across close/reopen cycles on one mounted picker", async ({
    pageA,
    api,
    sessionA,
    sessionB,
  }) => {
    const observations = recordPresenceGets(pageA);
    await gotoPortfolio(pageA);
    expect(
      observations.length,
      "presence must not be queried while the picker is closed (requirements.md 6.3)",
    ).toBe(0);

    // `EditHoldingsButton` keeps `AssetPicker` mounted and only toggles `open`,
    // so every opening below shares one page, one JS context, and therefore one
    // TanStack QueryClient. That is the point of this test: a fresh page per
    // opening would start from an empty cache and could never show that a
    // previous opening's `staleTime: Infinity` answer was *discarded* rather
    // than replayed. The sentinel is re-read after the last opening to prove no
    // reload silently reset that cache underneath the claim.
    await pageA.evaluate(() => {
      (window as unknown as { __presenceArcSentinel?: number }).__presenceArcSentinel = 1;
    });

    // ---- Opening 1: session A alone -> a real false, no banner ----
    let dialog = await openPicker(pageA);
    const firstOpening = await expectExactlyOneNewPresenceGet(observations, 0, "opening 1");
    expect(firstOpening.status).toBe(200);
    expect(
      firstOpening.anotherSessionActive,
      "a lone demo session must read false from the real endpoint",
    ).toBe(false);
    await expect(dialog.getByText(BANNER_TEXT)).toHaveCount(0);
    await closePicker(pageA);

    // ---- Opening 2: an independently logged-in session B is active -> a real true ----
    // B is touched immediately before this opening rather than earlier: the
    // overlay's TTL is short by design, so the gap between B's last touch and
    // A's read has to stay well inside it for `true` to be deterministic rather
    // than a race. B's own answer proves both sessions are live at once.
    expect(
      await presenceProbe(api, sessionB),
      "session B's own real presence read must see session A active",
    ).toBe(true);

    const beforeSecond = observations.length;
    dialog = await openPicker(pageA);
    const secondOpening = await expectExactlyOneNewPresenceGet(
      observations,
      beforeSecond,
      "opening 2, second session active",
    );
    expect(secondOpening.status).toBe(200);
    expect(
      secondOpening.anotherSessionActive,
      "a reopen on the same mounted picker must issue its own query rather than " +
        "replaying opening 1's cached false",
    ).toBe(true);
    // Requirement 6.4 - exactly one persistent advisory banner, consumed from
    // the response above rather than from a retained earlier assertion.
    await expect(dialog.getByText(BANNER_TEXT)).toHaveCount(1);

    // The banner is advisory only: editing still works, review is still
    // reachable, and nothing writes or re-polls. No composition is submitted.
    let putCount = 0;
    pageA.on("request", (request) => {
      if (request.method() === "PUT" && request.url().includes("/api/portfolio/holdings")) {
        putCount += 1;
      }
    });

    const heldTicker = await firstHeldTicker(api, sessionA);
    const heldCheckbox = dialog.getByRole("checkbox", {
      name: `Select ${heldTicker}`,
      exact: true,
    });
    await expect(heldCheckbox).toBeVisible({ timeout: 60_000 });
    await expect(heldCheckbox).toHaveAttribute("aria-checked", "true");
    await heldCheckbox.click();
    await expect(heldCheckbox).toHaveAttribute("aria-checked", "false");

    // Navigation to review still works - but nothing is ever submitted.
    await dialog.getByRole("button", { name: "Review changes" }).click();
    await expect(dialog.getByRole("button", { name: "Save changes" })).toBeVisible({
      timeout: 60_000,
    });

    expect(
      observations.length - beforeSecond,
      "editing and navigating to review must not re-query presence",
    ).toBe(1);
    await expect(dialog.getByText(BANNER_TEXT)).toHaveCount(1);
    expect(putCount, "presence must never trigger a holdings write").toBe(0);
    await closePicker(pageA);

    // ---- Opening 3: B is never touched again and ages out -> a real false ----
    // Closing the picker released nothing; only the TTL sweep ends session B.
    await new Promise((resolve) => setTimeout(resolve, EXPIRY_WAIT_MS));
    await expect
      .poll(() => presenceProbe(api, sessionA), {
        timeout: (TTL_SECONDS + 15) * 1_000,
        intervals: [2_000],
        message: "expected session B to age out of the presence set after its TTL",
      })
      .toBe(false);

    const beforeThird = observations.length;
    dialog = await openPicker(pageA);
    const thirdOpening = await expectExactlyOneNewPresenceGet(
      observations,
      beforeThird,
      "opening 3, after the other session expired",
    );
    expect(thirdOpening.status).toBe(200);
    expect(
      thirdOpening.anotherSessionActive,
      "once the other session has aged out, a later opening on the SAME mounted picker " +
        "must read false again - opening 2's cached true must not be replayed",
    ).toBe(false);
    await expect(dialog.getByText(BANNER_TEXT)).toHaveCount(0);
    await closePicker(pageA);

    // All three openings ran in one JS context, so the cache that had to be
    // superseded is the same cache that served opening 2's true.
    expect(
      await pageA.evaluate(
        () => (window as unknown as { __presenceArcSentinel?: number }).__presenceArcSentinel,
      ),
      "the page must not have reloaded between openings - a reload would reset the " +
        "QueryClient and void the cache-discard claim",
    ).toBe(1);

    expect(
      observations.length,
      "three openings on one mounted picker means exactly three presence GETs in total",
    ).toBe(3);
  });
});
