// @vitest-environment node
import type { APIRequestContext, Browser } from "@playwright/test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  DEMO_USER_ID,
  authenticateDemoSession,
  demoLoginCredentials,
  expect as playwrightExpect,
  test as demoTest,
  useIsolatedDemoContext,
} from "../demo-auth";
import type { DemoSession } from "../demo-auth";

/**
 * Contract tests for the B2 Task 9.6 demo-authenticated Playwright fixture.
 *
 * Node environment, deliberately: importing `@playwright/test` under Vitest's
 * default jsdom environment makes jsdom's synchronous XMLHttpRequest worker
 * issue a request against this checkout's Windows path, which Node rejects as
 * `Protocol "d:" not supported`. That surfaces as unhandled rejections that fail
 * the run even when every assertion passes. Nothing here needs a DOM — the
 * init-script boundary is exercised through a fake `localStorage` — so the node
 * environment is both the fix and the honest description of what is under test.
 */

/** The ordinary Golden-State E2E subject — never an acceptable demo login. */
const E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

const DEMO_EMAIL = "demo@wealthtracker.dev";
/** Distinctive fixture values so leak assertions cannot pass by coincidence. */
const DEMO_PASSWORD = "demo-password-fixture-value";
const DEMO_TOKEN = "demo-token-fixture-value";

const validSessionBody = {
  token: DEMO_TOKEN,
  userId: DEMO_USER_ID,
  email: DEMO_EMAIL,
  name: "Demo User",
};

const demoCredentials = { email: DEMO_EMAIL, password: DEMO_PASSWORD };

type RecordedCall = { url: string; data: unknown };

/**
 * A minimal `APIRequestContext` double that records every `post` and replays a
 * scripted response. Only the surface `authenticateDemoSession` is allowed to
 * touch is implemented, so any widening of that surface fails loudly here.
 */
function requestDouble(response: {
  ok?: boolean;
  status?: number;
  json?: () => Promise<unknown>;
}): { calls: RecordedCall[]; request: APIRequestContext } {
  const calls: RecordedCall[] = [];
  const stub = {
    post: async (url: string, options?: { data?: unknown }) => {
      calls.push({ url, data: options?.data });
      return {
        ok: () => response.ok ?? true,
        status: () => response.status ?? 200,
        json: response.json ?? (async () => validSessionBody),
      };
    },
  };
  return { calls, request: stub as unknown as APIRequestContext };
}

/** Runs `authenticateDemoSession` and returns the rejection message. */
async function loginFailureMessage(response: {
  ok?: boolean;
  status?: number;
  json?: () => Promise<unknown>;
}): Promise<string> {
  const { request } = requestDouble(response);
  try {
    await authenticateDemoSession(request, {
      apiBaseUrl: "http://gateway.test",
      credentials: demoCredentials,
    });
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
  throw new Error("expected authenticateDemoSession to reject, but it resolved");
}

describe("demoLoginCredentials", () => {
  it("returns the demo credentials from the injected environment", () => {
    expect(
      demoLoginCredentials({
        DEMO_TEST_EMAIL: DEMO_EMAIL,
        DEMO_TEST_PASSWORD: DEMO_PASSWORD,
      }),
    ).toEqual({ email: DEMO_EMAIL, password: DEMO_PASSWORD });
  });

  it("trims surrounding whitespace from the email", () => {
    expect(
      demoLoginCredentials({
        DEMO_TEST_EMAIL: `  ${DEMO_EMAIL}  `,
        DEMO_TEST_PASSWORD: DEMO_PASSWORD,
      }).email,
    ).toBe(DEMO_EMAIL);
  });

  it("preserves the password exactly, including surrounding whitespace", () => {
    expect(
      demoLoginCredentials({
        DEMO_TEST_EMAIL: DEMO_EMAIL,
        DEMO_TEST_PASSWORD: "  spaced-password  ",
      }).password,
    ).toBe("  spaced-password  ");
  });

  it("rejects a missing email", () => {
    expect(() =>
      demoLoginCredentials({ DEMO_TEST_PASSWORD: DEMO_PASSWORD }),
    ).toThrow(/DEMO_TEST_EMAIL/);
  });

  it("rejects a missing password", () => {
    expect(() => demoLoginCredentials({ DEMO_TEST_EMAIL: DEMO_EMAIL })).toThrow(
      /DEMO_TEST_PASSWORD/,
    );
  });

  it("rejects a whitespace-only email", () => {
    expect(() =>
      demoLoginCredentials({
        DEMO_TEST_EMAIL: "   ",
        DEMO_TEST_PASSWORD: DEMO_PASSWORD,
      }),
    ).toThrow(/DEMO_TEST_EMAIL/);
  });

  it("rejects a whitespace-only password", () => {
    expect(() =>
      demoLoginCredentials({
        DEMO_TEST_EMAIL: DEMO_EMAIL,
        DEMO_TEST_PASSWORD: "   ",
      }),
    ).toThrow(/DEMO_TEST_PASSWORD/);
  });

  it("names both demo variables and no other credential source", () => {
    let message = "";
    try {
      demoLoginCredentials({});
    } catch (error) {
      message = error instanceof Error ? error.message : String(error);
    }
    expect(message).toContain("DEMO_TEST_EMAIL");
    expect(message).toContain("DEMO_TEST_PASSWORD");
    expect(message).not.toContain("E2E_TEST_USER");
    expect(message).not.toContain("NEXT_PUBLIC_DEMO_PASSWORD");
  });

  it("never echoes the rejected password value", () => {
    let message = "";
    try {
      demoLoginCredentials({
        DEMO_TEST_EMAIL: "   ",
        DEMO_TEST_PASSWORD: DEMO_PASSWORD,
      });
    } catch (error) {
      message = error instanceof Error ? error.message : String(error);
    }
    expect(message).not.toContain(DEMO_PASSWORD);
  });

  it("has no built-in credential fallback when the environment is empty", () => {
    expect(() => demoLoginCredentials({})).toThrow();
  });
});

describe("authenticateDemoSession", () => {
  const savedApiBase = process.env.NEXT_PUBLIC_API_BASE_URL;
  const savedEmail = process.env.DEMO_TEST_EMAIL;
  const savedPassword = process.env.DEMO_TEST_PASSWORD;

  beforeEach(() => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;
    delete process.env.DEMO_TEST_EMAIL;
    delete process.env.DEMO_TEST_PASSWORD;
  });

  afterEach(() => {
    restore("NEXT_PUBLIC_API_BASE_URL", savedApiBase);
    restore("DEMO_TEST_EMAIL", savedEmail);
    restore("DEMO_TEST_PASSWORD", savedPassword);
  });

  function restore(key: string, value: string | undefined): void {
    if (value === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = value;
    }
  }

  it("posts exactly once to the gateway login route", async () => {
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, {
      apiBaseUrl: "http://gateway.test",
      credentials: demoCredentials,
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://gateway.test/api/auth/login");
  });

  it("sends exactly the resolved email and password", async () => {
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, {
      apiBaseUrl: "http://gateway.test",
      credentials: demoCredentials,
    });

    expect(calls[0].data).toEqual({
      email: DEMO_EMAIL,
      password: DEMO_PASSWORD,
    });
  });

  it("returns the four validated session fields", async () => {
    const { request } = requestDouble({});

    await expect(
      authenticateDemoSession(request, {
        apiBaseUrl: "http://gateway.test",
        credentials: demoCredentials,
      }),
    ).resolves.toEqual(validSessionBody);
  });

  it("normalizes trailing slashes on the API base", async () => {
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, {
      apiBaseUrl: "http://gateway.test///",
      credentials: demoCredentials,
    });

    expect(calls[0].url).toBe("http://gateway.test/api/auth/login");
  });

  it("defaults the API base to NEXT_PUBLIC_API_BASE_URL", async () => {
    process.env.NEXT_PUBLIC_API_BASE_URL = "http://from-env.test/";
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, { credentials: demoCredentials });

    expect(calls[0].url).toBe("http://from-env.test/api/auth/login");
  });

  it("falls back to localhost:8080 when no API base is configured", async () => {
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, { credentials: demoCredentials });

    expect(calls[0].url).toBe("http://localhost:8080/api/auth/login");
  });

  it("resolves credentials from the environment when none are passed", async () => {
    process.env.DEMO_TEST_EMAIL = DEMO_EMAIL;
    process.env.DEMO_TEST_PASSWORD = DEMO_PASSWORD;
    const { calls, request } = requestDouble({});

    await authenticateDemoSession(request, {
      apiBaseUrl: "http://gateway.test",
    });

    expect(calls[0].data).toEqual({
      email: DEMO_EMAIL,
      password: DEMO_PASSWORD,
    });
  });

  it("rejects a non-2xx login and reports the status", async () => {
    const message = await loginFailureMessage({ ok: false, status: 401 });

    expect(message).toContain("401");
  });

  it("rejects malformed JSON without embedding the response body", async () => {
    const message = await loginFailureMessage({
      json: async () => {
        throw new Error("Unexpected token < in JSON at position 0 :: <html>boom");
      },
    });

    expect(message).not.toContain("<html>boom");
  });

  it("rejects a blank token", async () => {
    const message = await loginFailureMessage({
      json: async () => ({ ...validSessionBody, token: "  " }),
    });

    expect(message).toContain("token");
  });

  it("rejects a missing name", async () => {
    const message = await loginFailureMessage({
      json: async () => ({
        token: DEMO_TOKEN,
        userId: DEMO_USER_ID,
        email: DEMO_EMAIL,
      }),
    });

    expect(message).toContain("name");
  });

  it("rejects the ordinary E2E subject", async () => {
    const message = await loginFailureMessage({
      json: async () => ({ ...validSessionBody, userId: E2E_USER_ID }),
    });

    expect(message).toContain("userId");
  });

  it("rejects a response email that differs from the requested email", async () => {
    const message = await loginFailureMessage({
      json: async () => ({ ...validSessionBody, email: "someone-else@example.test" }),
    });

    expect(message).toContain("email");
  });

  it("never leaks the password or token in any failure diagnostic", async () => {
    const messages = await Promise.all([
      loginFailureMessage({ ok: false, status: 500 }),
      loginFailureMessage({
        json: async () => {
          throw new Error(`bad json holding ${DEMO_TOKEN}`);
        },
      }),
      loginFailureMessage({
        json: async () => ({ ...validSessionBody, userId: E2E_USER_ID }),
      }),
      loginFailureMessage({
        json: async () => ({ ...validSessionBody, email: "someone-else@example.test" }),
      }),
    ]);

    for (const message of messages) {
      expect(message).not.toContain(DEMO_PASSWORD);
      expect(message).not.toContain(DEMO_TOKEN);
    }
  });
});

/** The exact key the application's own session reader uses. */
const AUTH_STORAGE_KEY = "wmpt.auth.session";

const validSession: DemoSession = { ...validSessionBody };

type CapturedInitScript = {
  callback: (arg: { key: string; value: DemoSession }) => void;
  arg: { key: string; value: DemoSession };
};

/**
 * A `Browser` double that records the context options it is handed and the
 * order of every lifecycle call, so ordering claims ("the session is installed
 * before the consumer runs", "the context always closes") are provable rather
 * than asserted by inspection.
 */
function browserDouble() {
  const events: string[] = [];
  const newContextOptions: Record<string, unknown>[] = [];
  const initScripts: CapturedInitScript[] = [];
  let pagesCreated = 0;

  const context = {
    addInitScript: async (
      callback: (arg: { key: string; value: DemoSession }) => void,
      arg: { key: string; value: DemoSession },
    ) => {
      events.push("addInitScript");
      initScripts.push({ callback, arg });
    },
    newPage: async () => {
      events.push("newPage");
      pagesCreated += 1;
      return { marker: "demo-page" };
    },
    close: async () => {
      events.push("close");
    },
  };

  const browser = {
    newContext: async (options: Record<string, unknown>) => {
      events.push("newContext");
      newContextOptions.push(options);
      return context;
    },
  };

  return {
    browser: browser as unknown as Browser,
    context,
    events,
    newContextOptions,
    initScripts,
    pagesCreated: () => pagesCreated,
  };
}

/** Invokes a captured init-script callback against a fake `window.localStorage`. */
function runInitScript(script: CapturedInitScript): Record<string, string> {
  const stored: Record<string, string> = {};
  const globals = globalThis as { window?: unknown };
  const hadWindow = "window" in globals;
  const originalWindow = globals.window;

  globals.window = {
    localStorage: {
      setItem: (key: string, value: string) => {
        stored[key] = value;
      },
    },
  };
  try {
    script.callback(script.arg);
  } finally {
    if (hadWindow) {
      globals.window = originalWindow;
    } else {
      delete globals.window;
    }
  }
  return stored;
}

describe("useIsolatedDemoContext", () => {
  it("creates the context with an explicit empty storage state", async () => {
    const { browser, newContextOptions } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {},
    );

    expect(newContextOptions[0].storageState).toEqual({
      cookies: [],
      origins: [],
    });
  });

  it("passes the configured baseURL through to the context", async () => {
    const { browser, newContextOptions } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {},
    );

    expect(newContextOptions[0].baseURL).toBe("http://app.test");
  });

  it("never references the suite's shared storage-state file", async () => {
    const { browser, newContextOptions } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {},
    );

    expect(JSON.stringify(newContextOptions[0])).not.toContain(".auth");
    expect(JSON.stringify(newContextOptions[0])).not.toContain("user.json");
  });

  it("installs the session init script before the consumer runs", async () => {
    const { browser, events } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {
        events.push("consumer");
      },
    );

    expect(events).toEqual(["newContext", "addInitScript", "consumer", "close"]);
  });

  it("hands the created context to the consumer", async () => {
    const { browser, context } = browserDouble();
    let received: unknown;

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async (ctx) => {
        received = ctx;
      },
    );

    expect(received).toBe(context);
  });

  it("leaves page creation to the caller", async () => {
    const { browser, pagesCreated } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {},
    );

    expect(pagesCreated()).toBe(0);
  });

  it("closes the context and rethrows when the consumer fails", async () => {
    const { browser, events } = browserDouble();
    const boom = new Error("consumer exploded");

    await expect(
      useIsolatedDemoContext(
        browser,
        { baseURL: "http://app.test", session: validSession },
        async () => {
          events.push("consumer");
          throw boom;
        },
      ),
    ).rejects.toBe(boom);

    expect(events).toEqual(["newContext", "addInitScript", "consumer", "close"]);
  });

  it("writes exactly the validated session under wmpt.auth.session", async () => {
    const { browser, initScripts } = browserDouble();

    await useIsolatedDemoContext(
      browser,
      { baseURL: "http://app.test", session: validSession },
      async () => {},
    );

    expect(initScripts).toHaveLength(1);
    const stored = runInitScript(initScripts[0]);

    expect(Object.keys(stored)).toEqual([AUTH_STORAGE_KEY]);
    expect(JSON.parse(stored[AUTH_STORAGE_KEY])).toEqual(validSession);
  });
});

describe("demo fixture module surface", () => {
  it("exports a Playwright test object Task 9.8 can import", () => {
    expect(typeof demoTest).toBe("function");
    expect(typeof demoTest.extend).toBe("function");
  });

  it("re-exports Playwright's expect beside the test object", () => {
    expect(typeof playwrightExpect).toBe("function");
  });
});
