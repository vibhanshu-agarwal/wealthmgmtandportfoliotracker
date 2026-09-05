/* eslint-disable react-hooks/rules-of-hooks --
 * Name-based false positives, not React. `eslint-config-next` applies
 * `react-hooks` repo-wide, and this file is a Playwright helper with no React in
 * it: Playwright names every fixture-provider's second parameter `use`, which the
 * rule reads as React 19's `use()` hook, and `useIsolatedDemoContext` matches the
 * `use*` custom-hook naming convention. Both names are fixed by Task 9.6's
 * published contract, so they cannot be renamed away. Task 9.8 will hit the same
 * rule; a `tests/e2e/**` override in `eslint.config.mjs` would be the durable fix.
 */
import { test as base, expect } from "@playwright/test";
import type {
  APIRequestContext,
  APIResponse,
  Browser,
  BrowserContext,
  Page,
} from "@playwright/test";

/**
 * Demo-authenticated Playwright fixture for the B2 showcase account.
 *
 * The demo account is a *different subject* than the suite's ordinary
 * Golden-State E2E user (`helpers/e2e-credentials.ts`,
 * `00000000-0000-0000-0000-000000000e2e`), and only this subject is authorized
 * by the gateway's `DemoResetAuthorizationFilter`. Tests that need it must not
 * borrow the shared, writable `storageState` the `chromium` project inherits —
 * hence a private browser context created here, per test.
 */

/**
 * The seeded showcase demo subject (`V15__Reconcile_Auth_Seed_Users.sql`).
 *
 * `scripts/check_b2_demo_identity.py` pins the gateway filter, the portfolio
 * initializer, and V15 to one another; this is a fourth, test-side copy that
 * guard does not currently cover. It is asserted against the live login
 * response on every run, so drift fails this fixture rather than silently
 * authenticating the wrong account.
 */
export const DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";

const LOGIN_PATH = "/api/auth/login";
const DEFAULT_API_BASE_URL = "http://localhost:8080";

/** The key `src/lib/auth/session.ts` reads the signed-in session from. */
const AUTH_STORAGE_KEY = "wmpt.auth.session";

export type DemoCredentials = {
  email: string;
  password: string;
};

export type DemoSession = {
  token: string;
  userId: string;
  email: string;
  name: string;
};

const SESSION_FIELDS = ["token", "userId", "email", "name"] as const;

/**
 * Resolves the demo login credentials from `DEMO_TEST_EMAIL` and
 * `DEMO_TEST_PASSWORD`.
 *
 * There is deliberately no fallback to the public deployment literals: Task 9.9
 * owns supplying these values to CI and parity-checking them, so a missing
 * variable must fail loudly here rather than silently authenticate against
 * whatever the defaults happen to be. The email is trimmed; the password is
 * passed through byte-for-byte and only checked for blankness, because
 * surrounding whitespace can be significant in a password.
 */
export function demoLoginCredentials(
  env: Record<string, string | undefined> = process.env,
): DemoCredentials {
  const email = (env.DEMO_TEST_EMAIL ?? "").trim();
  const password = env.DEMO_TEST_PASSWORD ?? "";
  if (!email || !password.trim()) {
    throw new Error(
      "[e2e] DEMO_TEST_EMAIL and DEMO_TEST_PASSWORD must both be set to non-blank " +
        "values for the demo-authenticated fixture. There is no credential fallback.",
    );
  }
  return { email, password };
}

/** Strips trailing slashes so the login path is appended exactly once. */
function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, "");
}

/** The only label a transport failure may carry when nothing is allowlisted. */
const TRANSPORT_FAILURE_LABEL = "transport failure";

/**
 * The exact `error.name` values that may be repeated in a diagnostic.
 *
 * An allowlist rather than a shape check: `error.name` is library- and
 * caller-controlled, and "looks alphabetic" is no evidence of non-sensitivity —
 * a bearer token is alphanumeric, and a name can be pure letters and still
 * carry a secret. Only an exact match here reaches the message; everything else
 * degrades to the constant above.
 */
const ALLOWED_TRANSPORT_ERROR_NAMES = new Set(["TimeoutError", "AbortError"]);

/** Describes a transport failure using only fixed, non-derived text. */
function describeTransportFailure(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  return ALLOWED_TRANSPORT_ERROR_NAMES.has(name)
    ? `${TRANSPORT_FAILURE_LABEL}: ${name}`
    : TRANSPORT_FAILURE_LABEL;
}

/**
 * Logs in as the demo account against the real gateway and returns the
 * validated session.
 *
 * Diagnostics stay sanitized on every failure path: status codes and field
 * names are safe to report, response bodies, credentials, and tokens are not.
 */
export async function authenticateDemoSession(
  request: APIRequestContext,
  options: {
    apiBaseUrl?: string;
    credentials?: DemoCredentials;
  } = {},
): Promise<DemoSession> {
  const credentials = options.credentials ?? demoLoginCredentials();
  const apiBaseUrl = normalizeBaseUrl(
    options.apiBaseUrl ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_API_BASE_URL,
  );

  let response: APIResponse;
  try {
    response = await request.post(`${apiBaseUrl}${LOGIN_PATH}`, {
      data: { email: credentials.email, password: credentials.password },
    });
  } catch (error) {
    // A transport failure (DNS, refused connection, timeout) rejects before any
    // response exists. Neither the thrown text nor the base URL is safe to
    // repeat: Playwright may quote the request in its message, and a base URL
    // can legitimately carry basic-auth credentials. Report the kind of failure
    // and the route only — and deliberately no `cause`, which would reprint the
    // raw message wherever the chain is logged.
    throw new Error(
      `[e2e] demo login could not reach POST ${LOGIN_PATH} ` +
        `(${describeTransportFailure(error)}). ` +
        "Check NEXT_PUBLIC_API_BASE_URL and that the gateway is reachable.",
    );
  }

  if (!response.ok()) {
    throw new Error(
      `[e2e] demo login failed with HTTP ${response.status()}. Check DEMO_TEST_EMAIL ` +
        "and DEMO_TEST_PASSWORD against the V15-seeded demo account.",
    );
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    // The parse error's message can quote the response body verbatim — drop it.
    throw new Error(
      `[e2e] demo login returned a malformed JSON body (HTTP ${response.status()}).`,
    );
  }

  return validateDemoSession(body, credentials.email);
}

/** Pins the login response to the seeded demo identity before any state is built. */
function validateDemoSession(body: unknown, requestedEmail: string): DemoSession {
  if (typeof body !== "object" || body === null) {
    throw new Error("[e2e] demo login response was not a JSON object.");
  }

  const record = body as Record<string, unknown>;
  for (const field of SESSION_FIELDS) {
    const value = record[field];
    if (typeof value !== "string" || !value.trim()) {
      throw new Error(
        `[e2e] demo login response is missing a non-blank \`${field}\`.`,
      );
    }
  }

  const session: DemoSession = {
    token: record.token as string,
    userId: record.userId as string,
    email: record.email as string,
    name: record.name as string,
  };

  if (session.userId !== DEMO_USER_ID) {
    throw new Error(
      `[e2e] demo login returned userId ${session.userId}, not the seeded demo ` +
        `subject ${DEMO_USER_ID}. Refusing to build demo browser state for a ` +
        "different account.",
    );
  }

  if (session.email !== requestedEmail) {
    throw new Error(
      "[e2e] demo login returned an email that differs from the requested " +
        "DEMO_TEST_EMAIL. Refusing to build demo browser state for a different account.",
    );
  }

  return session;
}

export type DemoAuthFixtures = {
  demoSession: DemoSession;
  demoContext: BrowserContext;
  demoPage: Page;
};

/**
 * Runs `use` against a private browser context holding only the demo session.
 *
 * The context starts from an explicit empty storage state rather than the
 * suite's `playwright/.auth/user.json`, so the ordinary E2E user's shared,
 * writable state is neither read nor mutated. There is deliberately no
 * storage-state *path* option: accepting one would reintroduce exactly the
 * sharing this fixture exists to prevent.
 *
 * The session is installed via `addInitScript` before any page is opened, so
 * React cannot read auth before hydration completes on static-export pages —
 * the same race `browser-auth.ts` documents. Page creation is left to the
 * caller. The context always closes, including when `use` throws.
 */
export async function useIsolatedDemoContext(
  browser: Browser,
  options: {
    baseURL?: string;
    session: DemoSession;
  },
  use: (context: BrowserContext) => Promise<void>,
): Promise<void> {
  const context = await browser.newContext({
    baseURL: options.baseURL,
    storageState: { cookies: [], origins: [] },
  });
  try {
    await context.addInitScript(
      (arg: { key: string; value: DemoSession }) => {
        window.localStorage.setItem(arg.key, JSON.stringify(arg.value));
      },
      { key: AUTH_STORAGE_KEY, value: options.session },
    );
    await use(context);
  } finally {
    await context.close();
  }
}

/**
 * The demo-authenticated `test` object for Task 9.8.
 *
 * `demoSession` exposes the bearer token for authenticated API setup and
 * cleanup; `demoPage` is the UI surface. The default `page` and `context`
 * fixtures are intentionally left untouched, so a spec can still reach the
 * ordinary E2E session from the same file when it needs both identities.
 */
export const test = base.extend<DemoAuthFixtures>({
  demoSession: async ({ request }, use) => {
    await use(await authenticateDemoSession(request));
  },
  demoContext: async ({ browser, baseURL, demoSession }, use) => {
    await useIsolatedDemoContext(browser, { baseURL, session: demoSession }, use);
  },
  demoPage: async ({ demoContext }, use) => {
    await use(await demoContext.newPage());
  },
});

export { expect };
