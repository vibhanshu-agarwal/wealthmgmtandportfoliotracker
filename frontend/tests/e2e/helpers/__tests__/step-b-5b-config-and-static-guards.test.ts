// @vitest-environment node
import { readdirSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PRICE_BATCH_SIZE } from "../../../production-e2e/lib/price-attribution";

const PRODUCTION_E2E = path.resolve(__dirname, "../../../production-e2e");
const CONFIG_FILE = path.join(PRODUCTION_E2E, "playwright.step-b-5b.config.ts");
const SPEC_FILE = path.join(PRODUCTION_E2E, "step-b-5b.spec.ts");
/** The app source that owns the price batch size the L6 attribution arithmetic mirrors. */
const APP_PORTFOLIO_API = path.resolve(__dirname, "../../../../src/lib/api/portfolio.ts");

function sourcesUnder(directory: string): Array<{ file: string; text: string }> {
  const found: Array<{ file: string; text: string }> = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...sourcesUnder(full));
    else if (entry.name.endsWith(".ts")) found.push({ file: path.relative(PRODUCTION_E2E, full), text: readFileSync(full, "utf8") });
  }
  return found;
}

const sources = sourcesUnder(PRODUCTION_E2E);
const libSources = sources.filter((source) => source.file.startsWith(`lib${path.sep}`));

describe("Step B 5b source guards", () => {
  it("covers the config, the spec and every lib module", () => {
    expect(sources.map((source) => source.file).sort()).toEqual(
      [
        "playwright.step-b-5b.config.ts",
        "step-b-5b.spec.ts",
        ...libSources.map((source) => source.file),
      ].sort(),
    );
    expect(libSources.length).toBeGreaterThanOrEqual(10);
  });

  it("intercepts no route and records nothing to disk beyond the ledger", () => {
    for (const { file, text } of sources) {
      expect(text, `${file} must not intercept routes`).not.toMatch(/\.route\s*\(|routeFromHAR|routeWebSocket/);
      expect(text, `${file} must not record HAR, video or traces`).not.toMatch(
        /recordHar|recordVideo|\.tracing\b|\.screenshot\s*\(|\.pdf\s*\(/,
      );
    }
  });

  it("imports nothing from the e2e helpers (localhost defaults, E2E identity) or the default e2e directory", () => {
    for (const { file, text } of sources) {
      expect(text, `${file} must not import e2e helpers`).not.toMatch(/from\s+["'][^"']*e2e\/helpers/);
      expect(text, `${file} must not import from tests/e2e`).not.toMatch(/from\s+["'][^"']*tests\/e2e/);
    }
  });

  it("never references an internal API route or the internal key header", () => {
    for (const { file, text } of sources) {
      expect(text.includes("/api/internal/"), `${file} must not reference any internal route`).toBe(false);
      expect(text, `${file} must not mention the internal key`).not.toMatch(/INTERNAL_API_KEY/);
    }
  });

  it("reads the environment only in the config and inside the spec's test body, never in lib", () => {
    for (const { file, text } of libSources) {
      expect(text, `${file} must not read process.env`).not.toMatch(/process\.env|import\.meta\.env/);
    }
    const spec = readFileSync(SPEC_FILE, "utf8");
    const firstEnvRead = spec.indexOf("process.env");
    const testStart = spec.indexOf("test(\"Wave 10.2 Step B 5b");
    expect(firstEnvRead).toBeGreaterThan(testStart);
    expect(spec.match(/process\.env/g)).toHaveLength(1);
  });

  it("logs nothing", () => {
    for (const { file, text } of sources) {
      expect(text, `${file} must not print`).not.toMatch(/console\.(log|info|warn|error|debug|dir|trace)/);
    }
  });

  it("declares exactly one test, so --list finds one test in one file", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec.match(/^test\(/gm)).toHaveLength(1);
    expect(spec).not.toMatch(/test\.describe|test\.only|test\.skip|test\.fixme|test\.beforeAll|test\.afterAll|test\.use/);
  });

  it("authenticates only through an init script and an empty storage state, never a login form", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec).toContain("addInitScript");
    expect(spec).not.toMatch(/getByLabel|getByPlaceholder|type=["']password|\.fill\([^)]*(password|token)/i);
    const storageStates = spec.match(/storageState[^\n]*/g) ?? [];
    expect(storageStates).toEqual(["storageState: { cookies: [], origins: [] },"]);
  });

  it("issues no login or other unlisted write from the spec itself", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec).not.toMatch(/auth\/login|\.post\(|\.delete\(|\.patch\(|method:\s*["'](POST|PUT|PATCH|DELETE)/);
  });

  it("names no proxy mechanism: no proxy option, proxy agent, dispatcher or proxy environment variable", () => {
    for (const { file, text } of sources) {
      expect(text, `${file} must not set a proxy option`).not.toMatch(/\bproxy\s*:/i);
      expect(text, `${file} must not use a proxy agent or dispatcher`).not.toMatch(
        /ProxyAgent|setGlobalDispatcher|\bdispatcher\b|from\s+["']undici/,
      );
      expect(text, `${file} must not mention a proxy environment variable`).not.toMatch(
        /\b(HTTPS?|ALL|NO)_PROXY\b|NODE_USE_ENV_PROXY|--use-env-proxy/i,
      );
    }
  });

  it("makes the out-of-page read with the plain global fetch: the only fetch call, no agent, no options of its own", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec.match(/\bfetch\(/g)).toEqual(["fetch("]);
    expect(spec).toContain("fetchImpl: (url, init) => fetch(url, init),");
    const independentRead = readFileSync(path.join(PRODUCTION_E2E, "lib", "independent-read.ts"), "utf8");
    expect(independentRead).toContain('redirect: "error"');
  });

  it("writes the armed event exactly once, immediately before the Save click, with nothing in between", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec.match(/ctx\.arm\(\)/g)).toHaveLength(1);
    expect(spec.match(/writeArmed\(/g)).toHaveLength(1);
    expect(spec.match(/saveButton\.click\(/g)).toHaveLength(1);

    const armAt = spec.indexOf("ctx.arm()");
    const saveClickAt = spec.indexOf("await saveButton.click");
    expect(armAt).toBeGreaterThan(-1);
    expect(saveClickAt).toBeGreaterThan(armAt);
    // It belongs to L8, not to some other leg.
    expect(armAt).toBeGreaterThan(spec.indexOf("async function runL8"));
    expect(armAt).toBeLessThan(spec.indexOf("async function runL9"));

    const between = spec.slice(armAt, saveClickAt);
    expect(between).not.toMatch(/\.(click|dblclick|check|uncheck|press|tap|fill|dispatchEvent)\(/);
    expect(between.replace(/\s+/g, " ").trim()).toBe("ctx.arm();");
  });

  it("clicks exactly once in L8 after arming, and that click is Save", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    const runL8 = spec.slice(spec.indexOf("async function runL8"), spec.indexOf("async function runL9"));
    const afterArm = runL8.slice(runL8.indexOf("ctx.arm()"));
    expect(afterArm.match(/\.click\(/g)).toHaveLength(1);
    expect(afterArm.indexOf(".click(")).toBe(afterArm.indexOf("saveButton.click(") + "saveButton".length);
  });

  it("mirrors the app's own price batch size, so a change there cannot silently break L6 attribution", () => {
    const source = readFileSync(APP_PORTFOLIO_API, "utf8");
    expect(source.match(/\bMARKET_PRICE_BATCH_SIZE\s*=/g)).toHaveLength(1);
    const declared = /const\s+MARKET_PRICE_BATCH_SIZE\s*=\s*(\d+)\s*;/.exec(source);
    expect(declared).not.toBeNull();
    expect(PRICE_BATCH_SIZE).toBe(Number(declared![1]));
    expect(PRICE_BATCH_SIZE).toBe(25);
  });

  it("wires L0 to the origin-and-path check, so a foreign origin serving /portfolio does not pass", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec).toContain('urlIsOnOriginAtPath(page.url(), config.frontendOrigin, "/portfolio")');
    expect(spec).not.toMatch(/pathnameOf\(/);
  });

  it("wires L1 to fail on a non-200 last portfolio read, matching the pass table", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    expect(spec).toContain("check.failIf(portfolioLoadStatus !== 200, failureReasonForStatus(portfolioLoadStatus))");
  });

  it("wires L6 to judge and record only the attributed picker requests", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    const runL6 = spec.slice(spec.indexOf("async function runL6"), spec.indexOf("async function runL7"));
    expect(runL6).toContain("priceLegFailure(evaluation, unchecked)");
    expect(runL6).toContain("priceHttpEntries(observations, attribution.predicted)");
    expect(runL6).toContain("facts.priceRequestsAfterUncheck = evaluation.priceRequestsAfterUncheck");
    // No way back to the old behaviour: recording every price request after the mark, or a hand-rolled verdict.
    expect(runL6).not.toMatch(/httpOf\(/);
    expect(runL6).not.toMatch(/failureReasonForStatus\(|check\.failIf\(/);
    expect(runL6).not.toContain('"/api/market/prices"');
  });

  it("re-checks the run-wide guard through finish() and still throws at the end of the test for a tripped guard", () => {
    const spec = readFileSync(SPEC_FILE, "utf8");
    const finishAt = spec.lastIndexOf("runner.finish()");
    const guardThrowAt = spec.indexOf("if (runner.guardTripped) throw");
    const failedThrowAt = spec.indexOf("if (runner.failed) throw");
    expect(finishAt).toBeGreaterThan(spec.indexOf('test("Wave 10.2 Step B 5b'));
    expect(guardThrowAt).toBeGreaterThan(finishAt);
    expect(failedThrowAt).toBeGreaterThan(guardThrowAt);
    // finish() is the first statement of the finally block, so it runs however the legs ended.
    expect(spec).toMatch(/finally\s*\{\s*(\/\/[^\n]*\n\s*)*runner\.finish\(\);/);
    // The guard is handed to the runner, so finish() has something to evaluate.
    expect(spec).toContain("guard: () => recorder.foreignAuthorizedRequestSeen()");
  });
});

/** The source of one leg function: from its declaration up to the next leg's declaration (or the test section). */
function legSource(spec: string, leg: string): string {
  const start = spec.indexOf(`async function run${leg}(`);
  expect(start, `run${leg} exists`).toBeGreaterThan(-1);
  const end = spec.slice(start + 1).search(/\nasync function runL\d\(|\n\/\/ -- the test/);
  return end === -1 ? spec.slice(start) : spec.slice(start, start + 1 + end);
}

describe("Step B 5b spec reports what the pass tables demand (ledger-contract.json passHttp)", () => {
  const spec = () => readFileSync(SPEC_FILE, "utf8");

  it("splits the spec into its ten legs, in order", () => {
    const source = spec();
    for (const leg of ["L0", "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9"]) {
      expect(legSource(source, leg).startsWith(`async function run${leg}(`), leg).toBe(true);
      expect(legSource(source, leg).match(/async function runL\d\(/g), leg).toHaveLength(1);
    }
    expect(legSource(source, "L9")).not.toContain("test(");
  });

  it("reports no http for L0, L3 and L7, exactly as their passHttp entries say", () => {
    const source = spec();
    for (const leg of ["L0", "L3", "L7"]) {
      const body = legSource(source, leg);
      expect(body, leg).toMatch(/check\.report\(\[\], \{/);
      expect(body, leg).not.toMatch(/httpOf\(|priceHttpEntries\(/);
    }
  });

  it("records, for L1, the SETTLED portfolio reads only, with the load status read at the same synchronous point", () => {
    const body = legSource(spec(), "L1");
    const readsAt = body.indexOf("const portfolioReads = recorder.settledPortfolioReads();");
    const statusAt = body.indexOf("const portfolioLoadStatus = recorder.lastPortfolioStatus();");
    expect(readsAt).toBeGreaterThan(-1);
    expect(statusAt).toBeGreaterThan(readsAt);
    // Nothing that yields to the page between the two reads, so the status is always the last recorded entry's.
    expect(body.slice(readsAt, statusAt)).not.toMatch(/\bawait\b/);
    expect(body).toContain("check.report(httpOf(portfolioReads), {");
    expect(body).not.toMatch(/recorder\.find\("GET", "\/api\/portfolio"/);
  });

  it("records the same requests its count and status facts describe, through the shared helpers", () => {
    const source = spec();
    expect(legSource(source, "L2")).toContain('check.report(httpOf(recorder.find("GET", "/api/portfolio/summary")), {');
    expect(legSource(source, "L4")).toContain('check.report(httpOf(recorder.find("GET", "/api/assets", shared.openMark)), facts)');
    expect(legSource(source, "L4")).toContain("catalogStatus: ledgerStatusOf(entry),");
    expect(legSource(source, "L5")).toContain("check.report(httpOf(requests), {");
    expect(legSource(source, "L5")).toContain("presenceRequestsSinceOpen: requests.length,");
    expect(legSource(source, "L5")).toContain("presenceStatus: ledgerStatusOf(first),");
    expect(legSource(source, "L8")).toContain("facts.putCount = puts.length;");
    expect(legSource(source, "L8")).toContain("facts.putStatus = ledgerStatusOf(put);");
    expect(legSource(source, "L8")).toContain("check.report(httpOf(puts), facts)");
    expect(legSource(source, "L9")).toContain("facts.putCount = resets.length;");
    expect(legSource(source, "L9")).toContain("facts.putStatus = ledgerStatusOf(reset);");
    expect(legSource(source, "L9")).toContain("check.report(httpOf(resets), facts)");
    // One mapping for every status fact and every recorded entry: no private "failed ? 0" copy that could drift.
    expect(source.match(/ledgerStatusOf\(/g)).toHaveLength(4);
    expect(source).not.toMatch(/function httpOf|const httpOf/);
    expect(source).toMatch(/import \{[^}]*\bhttpOf\b[^}]*\} from "\.\/lib\/page-recorder";/);
  });

  it("records only the attributed price requests for L6, with the count taken from the same evaluation", () => {
    const body = legSource(spec(), "L6");
    expect(body).toContain("facts.priceRequestsAfterUncheck = evaluation.priceRequestsAfterUncheck;");
    expect(body).toContain("return check.report(priceHttpEntries(observations, attribution.predicted), facts);");
  });

  it("paces before the save: the page's own reads must be quiet before the write is armed, and giving up sends nothing", () => {
    const l8 = legSource(spec(), "L8");
    expect(l8.match(/waitForQuietPage\(ctx\)/g)).toHaveLength(1);
    const quietAt = l8.indexOf("waitForQuietPage(ctx)");
    const armAt = l8.indexOf("ctx.arm()");
    expect(quietAt).toBeGreaterThan(-1);
    expect(armAt).toBeGreaterThan(quietAt);
    // Before the arm, never between the arm and the click.
    const afterQuiet = l8.slice(quietAt, l8.indexOf("await saveButton.click"));
    expect(afterQuiet.match(/ctx\.arm\(\)/g)).toHaveLength(1);
    // The version the save carries is read after the wait, "as late as possible".
    expect(l8.indexOf("const latest = recorder.lastGoodPortfolio();")).toBeGreaterThan(quietAt);
    // Failing to go quiet is a TIMEOUT recorded BEFORE anything is armed or sent.
    const giveUp = /if \(!\(await waitForQuietPage\(ctx\)\)\) \{\s*check\.fail\("TIMEOUT"\);\s*return check\.report\(\[\], facts\);\s*\}/.exec(l8);
    expect(giveUp).not.toBeNull();
    expect(giveUp!.index).toBeLessThan(armAt);
    // ...and nothing that touches the page runs between the wait and the arm.
    expect(l8.slice(quietAt, armAt)).not.toMatch(/\.(click|fill|check|uncheck|press)\(/);
  });

  it("paces before the reset click in the same way", () => {
    const l9 = legSource(spec(), "L9");
    expect(l9.match(/waitForQuietPage\(ctx\)/g)).toHaveLength(1);
    const quietAt = l9.indexOf("waitForQuietPage(ctx)");
    const clickAt = l9.indexOf("await button.click");
    expect(quietAt).toBeGreaterThan(-1);
    expect(clickAt).toBeGreaterThan(quietAt);
    expect(l9.slice(quietAt, clickAt)).not.toMatch(/\.(click|fill|check|uncheck|press)\(/);
    expect(l9).toMatch(/if \(!\(await waitForQuietPage\(ctx\)\)\) \{\s*check\.fail\("TIMEOUT"\);\s*return check\.report\(\[\], facts\);\s*\}/);
  });

  it("bounds the pacing by the action timeout on the injected clock, and stamps settle times from that same clock", () => {
    const source = spec();
    expect(source).toContain("waitForQuietPageReads(ctx.recorder, pollOptions(ctx))");
    expect(source).toContain("const clock: Clock = realClock;");
    expect(source).toContain("now: () => clock.now(),");
    expect(source).toMatch(/golden,\s*clock,\s*fetchImpl:/);
    // pollOptions is where the action timeout and the clock come from.
    expect(source).toContain("return { timeoutMs: ctx.config.actionTimeoutMs, intervalMs: POLL_INTERVAL_MS, clock: ctx.clock };");
  });
});

describe("Step B 5b Playwright config", () => {
  const original = { work: process.env.STEP_B_5B_WORK_DIR, timeout: process.env.STEP_B_5B_ACTION_TIMEOUT_MS };

  afterEach(() => {
    if (original.work === undefined) delete process.env.STEP_B_5B_WORK_DIR;
    else process.env.STEP_B_5B_WORK_DIR = original.work;
    if (original.timeout === undefined) delete process.env.STEP_B_5B_ACTION_TIMEOUT_MS;
    else process.env.STEP_B_5B_ACTION_TIMEOUT_MS = original.timeout;
    vi.resetModules();
  });

  async function loadConfig() {
    vi.resetModules();
    const loaded = (await import("../../../production-e2e/playwright.step-b-5b.config")) as { default: Record<string, unknown> };
    return loaded.default as {
      testDir: string;
      testMatch: RegExp;
      outputDir: string;
      preserveOutput: string;
      timeout: number;
      expect: { timeout: number };
      forbidOnly: boolean;
      retries: number;
      workers: number;
      reporter: unknown;
      globalSetup?: unknown;
      globalTeardown?: unknown;
      webServer?: unknown;
      use: Record<string, unknown>;
      projects: Array<{ name: string; use: Record<string, unknown>; dependencies?: unknown; testMatch?: unknown }>;
    };
  }

  it("loads with no STEP_B_5B variables set, and never needs any", async () => {
    delete process.env.STEP_B_5B_WORK_DIR;
    delete process.env.STEP_B_5B_ACTION_TIMEOUT_MS;
    const config = await loadConfig();
    expect(config.outputDir).toBe(path.join(os.tmpdir(), "wave10-5b-work", "pw-output"));
    expect(config.use.actionTimeout).toBe(60_000);
    expect(config.expect.timeout).toBe(60_000);
    expect(config.timeout).toBe(900_000);
  });

  it("has no globalSetup, globalTeardown, webServer, setup project, dependency or storage state", async () => {
    const config = await loadConfig();
    expect(config.globalSetup).toBeUndefined();
    expect(config.globalTeardown).toBeUndefined();
    expect(config.webServer).toBeUndefined();
    expect(config.projects).toHaveLength(1);
    expect(config.projects[0].dependencies).toBeUndefined();
    expect(config.projects[0].use.storageState).toBeUndefined();
    expect(config.use.storageState).toBeUndefined();
  });

  it("collects exactly the one spec from its own directory", async () => {
    const config = await loadConfig();
    expect(path.resolve(config.testDir)).toBe(PRODUCTION_E2E);
    expect(config.testMatch.test("step-b-5b.spec.ts")).toBe(true);
    for (const other of ["asset-picker.spec.ts", "demo-reset.spec.ts", "step-b-5b.setup.ts", "lib/ledger.ts", "step-b-5b.spec.ts.bak"]) {
      expect(config.testMatch.test(other), other).toBe(false);
    }
  });

  it("launches Chromium with --no-proxy-server, so the token-bearing API traffic can only go direct", async () => {
    const config = await loadConfig();
    expect(config.use.launchOptions).toEqual({ args: ["--no-proxy-server"] });
  });

  it("keeps that launch argument once Playwright merges the project over the top-level use, and sets no proxy option", async () => {
    const config = await loadConfig();
    // Playwright resolves a project's use as { ...config.use, ...project.use }, skipping undefined values.
    const projectUse = Object.fromEntries(Object.entries(config.projects[0].use).filter(([, value]) => value !== undefined));
    const resolved = { ...config.use, ...projectUse } as {
      launchOptions?: { args?: string[] };
      proxy?: unknown;
      channel?: unknown;
      headless?: unknown;
    };
    expect(resolved.launchOptions?.args).toContain("--no-proxy-server");
    // No conflicting proxy setting, at either level or as another launch argument.
    expect(config.use.proxy).toBeUndefined();
    expect(config.projects[0].use.proxy).toBeUndefined();
    expect(resolved.proxy).toBeUndefined();
    expect((resolved.launchOptions?.args ?? []).filter((arg) => /^--proxy|^--no-proxy-server$/.test(arg))).toEqual([
      "--no-proxy-server",
    ]);
    // The browser channel and the proxy switch coexist in the resolved project: naming the full Chromium build
    // drops no launch argument (the channel only picks the executable), and headless stays on.
    expect(resolved.channel).toBe("chromium");
    expect(resolved.headless).toBe(true);
  });

  it("selects the full Chromium build with channel \"chromium\", the binary the orchestrator's P0 probe checks", async () => {
    const config = await loadConfig();
    expect(config.projects[0].use.channel).toBe("chromium");
    // Headless stays on: the channel picks the executable (Playwright's new headless mode), not a visible window.
    expect(config.use.headless).toBe(true);
    // Nothing at the top level may hand the project a different build to inherit.
    expect(config.use.channel === undefined || config.use.channel === "chromium").toBe(true);
    // The configuration names no browser executable path of its own: the channel is the only selector.
    expect(config.use.executablePath).toBeUndefined();
    expect((config.use.launchOptions as Record<string, unknown>).executablePath).toBeUndefined();
    expect(config.projects[0].use.executablePath).toBeUndefined();
  });

  /**
   * Playwright's own executable naming, read offline: nothing below launches a browser, spawns a process or opens a
   * socket. `chromium.executablePath()` is what the orchestrator's P0 probe checks; the launch resolves its binary
   * through `getExecutableName` and the registry. Required lazily, so a layout change inside playwright-core fails
   * these two tests instead of the whole file's collection.
   */
  function playwrightNaming() {
    const nodeRequire = createRequire(__filename);
    // The public entry first: the server modules only initialise in the order the package itself loads them.
    const core = nodeRequire("playwright-core") as { chromium: { executablePath(): string } };
    const { registry } = nodeRequire("playwright-core/lib/server/registry/index") as {
      registry: { findExecutable(name: string): { executablePath(): string | undefined } | undefined };
    };
    const chromiumModule = path.join(
      path.dirname(nodeRequire.resolve("playwright-core/package.json")),
      "lib",
      "server",
      "chromium",
      "chromium.js",
    );
    const { Chromium } = nodeRequire(chromiumModule) as {
      Chromium: { prototype: { getExecutableName(options: { headless?: boolean; channel?: string }): string } };
    };
    return {
      probed: (): string => core.chromium.executablePath(),
      launched: (options: { headless?: boolean; channel?: string }): string | undefined =>
        registry.findExecutable(Chromium.prototype.getExecutableName.call({}, options))?.executablePath(),
    };
  }

  it("launches the very executable the orchestrator's P0 probe checks (chromium.executablePath()), not the headless shell", async () => {
    const config = await loadConfig();
    const projectUse = Object.fromEntries(Object.entries(config.projects[0].use).filter(([, value]) => value !== undefined));
    const resolved = { ...config.use, ...projectUse } as { headless?: boolean; channel?: string };
    const naming = playwrightNaming();
    const probed = naming.probed();
    expect(probed).toBeTruthy();
    expect(naming.launched({ headless: resolved.headless, channel: resolved.channel })).toBe(probed);
  });

  it("would launch a different build without the channel, which is the mismatch that channel exists to prevent", () => {
    // Positive control for the test above: with headless on and no channel, Playwright resolves the separate
    // headless-shell build, so the probe would have measured a binary the child never starts.
    const naming = playwrightNaming();
    const withoutChannel = naming.launched({ headless: true });
    expect(withoutChannel).toBeTruthy();
    expect(withoutChannel).not.toBe(naming.probed());
  });

  it("is serial, never retries, forbids test.only, reports nothing and records no artifacts", async () => {
    const config = await loadConfig();
    expect(config).toMatchObject({ workers: 1, retries: 0, forbidOnly: true, reporter: "null", preserveOutput: "never" });
    expect(config.use).toMatchObject({ trace: "off", video: "off", screenshot: "off", acceptDownloads: false, serviceWorkers: "block" });
  });

  it("places Playwright output under the work directory, away from the ledger", async () => {
    const work = path.resolve("/step-b-5b-test/work");
    process.env.STEP_B_5B_WORK_DIR = work;
    const config = await loadConfig();
    expect(config.outputDir).toBe(path.join(work, "pw-output"));
    expect(config.outputDir).not.toBe(work);
  });

  it("takes its timeouts from STEP_B_5B_ACTION_TIMEOUT_MS and falls back safely on a bad value", async () => {
    process.env.STEP_B_5B_ACTION_TIMEOUT_MS = "30000";
    let config = await loadConfig();
    expect(config.use).toMatchObject({ actionTimeout: 30_000, navigationTimeout: 30_000 });
    expect(config.expect.timeout).toBe(30_000);
    expect(config.timeout).toBe(450_000);

    process.env.STEP_B_5B_ACTION_TIMEOUT_MS = "not-a-number";
    config = await loadConfig();
    expect(config.use.actionTimeout).toBe(60_000);
  });

  it("caps the test timeout below the orchestrator's 2,700 second deadline, leaving room for the slowest start", async () => {
    process.env.STEP_B_5B_ACTION_TIMEOUT_MS = "600000";
    const config = await loadConfig();
    expect(config.timeout).toBeLessThan(2_700_000);
    expect(config.timeout).toBe(1_800_000);
    expect(config.timeout + 900_000).toBeLessThanOrEqual(2_700_000);
  });

  it("gives ten legs at the orchestrator's default action timeout of 120 s a test timeout that comfortably exceeds them", async () => {
    process.env.STEP_B_5B_ACTION_TIMEOUT_MS = "120000";
    const config = await loadConfig();
    expect(config.use).toMatchObject({ actionTimeout: 120_000, navigationTimeout: 120_000 });
    expect(config.expect.timeout).toBe(120_000);
    expect(config.timeout).toBeGreaterThanOrEqual(1.5 * 10 * 120_000);
    expect(config.timeout).toBe(1_800_000);
  });

  it("derives its test timeout from the shared formula, not a private copy that could drift", () => {
    const source = readFileSync(CONFIG_FILE, "utf8");
    expect(source).toContain("resolveTestTimeoutMs(actionTimeoutMs)");
    expect(source).not.toMatch(/actionTimeoutMs\s*\*\s*\d/);
  });

  it("is the file the orchestrator invokes, and its header names the real invocation: node on the package's cli.js, never npx", () => {
    const source = readFileSync(CONFIG_FILE, "utf8");
    expect(source).toContain("tests/production-e2e/playwright.step-b-5b.config.ts");
    expect(source).toContain(
      "node node_modules/@playwright/test/cli.js test -c tests/production-e2e/playwright.step-b-5b.config.ts",
    );
    // No command line that starts npx or npm: the header only mentions them to say they are not used.
    expect(source).not.toMatch(/^\s*\/\/\s+(npx|npm)\s/m);
    expect(source).not.toMatch(/npx\s+playwright/);
  });
});
