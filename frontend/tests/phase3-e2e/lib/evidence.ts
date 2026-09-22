/**
 * Evidence writers and the per-context browser monitor.
 *
 * Shareable files (ledger, network log, final state, provenance) pass through the
 * SecretRegistry before every write. The monitor implements the common oracles: page
 * errors, console errors, HTTP >= 400, non-target origins. Each event is tagged with
 * the scenario that was current when it happened; a scenario asserts only its own.
 */
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, type BrowserContext, type Page, type Request, type Route } from "@playwright/test";
import { classifyConsoleError, classifyHttpFailure, resourceFailureStatus } from "./classify";
import { SecretRegistry, sanitizeUrl } from "./sanitize";

export class Evidence {
  readonly secrets = new SecretRegistry();
  readonly screenshotDir: string;

  constructor(readonly runDir: string) {
    this.screenshotDir = path.join(runDir, "screenshots");
    mkdirSync(this.screenshotDir, { recursive: true });
  }

  private appendLine(file: string, record: Record<string, unknown>): void {
    this.secrets.assertClean(record);
    appendFileSync(path.join(this.runDir, file), `${JSON.stringify(record)}\n`);
  }

  writeJson(file: string, value: unknown): void {
    this.secrets.assertClean(value);
    writeFileSync(path.join(this.runDir, file), `${JSON.stringify(value, null, 2)}\n`);
  }

  /** Records one assertion in the ledger without asserting; returns the outcome. */
  record(scenario: string, check: string, pass: boolean, detail: Record<string, unknown> = {}): boolean {
    this.appendLine("ledger.jsonl", { at: new Date().toISOString(), scenario, check, pass, ...detail });
    return pass;
  }

  /** Records one assertion in the ledger, then hard-asserts it. */
  verify(scenario: string, check: string, pass: boolean, detail: Record<string, unknown> = {}): void {
    this.record(scenario, check, pass, detail);
    expect(pass, `${scenario}: ${check} ${JSON.stringify(detail)}`).toBe(true);
  }

  /** Records a non-failing observation (a finding for triage, not an assertion). */
  observe(scenario: string, observation: string, detail: Record<string, unknown> = {}): void {
    this.appendLine("ledger.jsonl", { at: new Date().toISOString(), scenario, observation, ...detail });
  }

  network(record: Record<string, unknown>): void {
    this.appendLine("network.jsonl", record);
  }

  async screenshot(page: Page, scenario: string, name: string): Promise<void> {
    await page.screenshot({ path: path.join(this.screenshotDir, `${scenario}-${name}.png`), fullPage: true });
  }
}

export interface DeclaredFailure {
  readonly method: string;
  readonly pathname: string;
  readonly status: number;
}

interface MonitorEvent {
  readonly scenario: string;
  readonly kind: "pageerror" | "console" | "http" | "non-target" | "noise" | "identity";
  readonly detail: Record<string, unknown>;
  readonly url?: string;
}

export type RouteHook = (route: Route, request: Request) => Promise<boolean>;

export const DEMO_ACCOUNT_EMAIL = "demo@wealthtracker.dev";
const AUTH_PATHS = new Set(["/api/auth/login", "/api/auth/signup"]);
const STRICT_PATH = /^\/api\/(insights(\/|$)|chat(\/|$))/;

export interface MonitorOptions {
  readonly role: string;
  readonly frontend: string;
  readonly api: string;
  readonly evidence: Evidence;
  readonly currentScenario: () => string;
  /** Lower-cased emails of this run's identities; any other auth email is blocked. */
  readonly allowedAuthEmails: ReadonlySet<string>;
  /** Spaces every auth request (shared, file-backed pacer). */
  readonly paceAuth: () => Promise<void>;
  /** Spaces strict-bucket requests (insights, chat) for this role's user. */
  readonly paceStrict: () => Promise<void>;
  /** Called once per browser auth request that is allowed through. */
  readonly onAuthRequest: (pathname: string) => void;
  /** Negative-control fault injection (local only). Returns true when it handled the request. */
  readonly routeHook?: RouteHook;
}

export class ContextMonitor {
  private readonly events: MonitorEvent[] = [];
  private readonly declared: Array<DeclaredFailure & { scenario: string; used: boolean }> = [];

  private constructor(private readonly context: BrowserContext, private readonly options: MonitorOptions) {}

  static async attach(context: BrowserContext, options: MonitorOptions): Promise<ContextMonitor> {
    const monitor = new ContextMonitor(context, options);
    await monitor.install();
    return monitor;
  }

  /** Exempts exactly one matching failure in the current scenario. The scenario still asserts it happened. */
  declare(failure: DeclaredFailure): void {
    this.declared.push({ ...failure, scenario: this.options.currentScenario(), used: false });
  }

  private consumeDeclared(method: string | null, pathname: string, status: number, scenario: string): boolean {
    const match = this.declared.find(
      (d) => !d.used && d.scenario === scenario && d.pathname === pathname && d.status === status && (method === null || d.method === method),
    );
    if (!match) return false;
    match.used = true;
    return true;
  }

  private isTargetOrigin(origin: string): boolean {
    return origin === this.options.frontend || origin === this.options.api;
  }

  private async install(): Promise<void> {
    const { evidence, role } = this.options;

    await this.context.route("**/*", async (route, request) => {
      let origin: string;
      try {
        origin = new URL(request.url()).origin;
      } catch {
        await route.abort("failed");
        return;
      }
      if (!this.isTargetOrigin(origin)) {
        this.events.push({
          scenario: this.options.currentScenario(),
          kind: "non-target",
          detail: { origin, method: request.method() },
        });
        await route.abort("blockedbyclient");
        return;
      }
      const pathname = new URL(request.url()).pathname;
      if (origin === this.options.api && request.method() === "POST" && AUTH_PATHS.has(pathname)) {
        // Identity guard: the email is inspected in memory only and never recorded.
        let email = "";
        try {
          email = String((request.postDataJSON() as { email?: unknown } | null)?.email ?? "").toLowerCase();
        } catch {
          email = "";
        }
        if (email === DEMO_ACCOUNT_EMAIL || !this.options.allowedAuthEmails.has(email)) {
          this.events.push({
            scenario: this.options.currentScenario(),
            kind: "identity",
            detail: { pathname, isDemo: email === DEMO_ACCOUNT_EMAIL, isRunIdentity: false },
          });
          await route.abort("blockedbyclient");
          return;
        }
        await this.options.paceAuth();
        this.options.onAuthRequest(pathname);
      }
      if (origin === this.options.api && STRICT_PATH.test(pathname)) await this.options.paceStrict();
      if (this.options.routeHook && (await this.options.routeHook(route, request))) return;
      await route.continue();
    });

    const watchPage = (page: Page) => {
      page.on("pageerror", (error) => {
        this.events.push({
          scenario: this.options.currentScenario(),
          kind: "pageerror",
          detail: { message: error.message.slice(0, 200) },
        });
      });
      page.on("console", (message) => {
        if (message.type() !== "error") return;
        const scenario = this.options.currentScenario();
        const text = message.text();
        const locationUrl = message.location().url ?? "";
        if (classifyConsoleError(text, locationUrl, this.options.frontend) === "next-segment-prefetch-404") {
          // Accepted as noise only if the same URL was also observed as an HTTP 404 (checked in assertClean).
          this.events.push({ scenario, kind: "noise", detail: { class: "next-segment-prefetch-404", source: "console" }, url: locationUrl });
          return;
        }
        const status = resourceFailureStatus(text);
        if (status !== null && locationUrl && this.declaredConsoleMatch(locationUrl, status, scenario)) return;
        this.events.push({ scenario, kind: "console", detail: { text: text.slice(0, 200) } });
      });
      page.on("response", (response) => {
        const status = response.status();
        const request = response.request();
        const scenario = this.options.currentScenario();
        const url = sanitizeUrl(response.url());
        evidence.network({
          at: new Date().toISOString(),
          scenario,
          role,
          method: request.method(),
          origin: url.origin,
          path: url.path,
          queryKeys: url.queryKeys,
          status,
          resourceType: request.resourceType(),
        });
        if (status < 400) return;
        if (classifyHttpFailure(response.url(), status, this.options.frontend) === "next-segment-prefetch-404") {
          this.events.push({ scenario, kind: "noise", detail: { class: "next-segment-prefetch-404", source: "http" }, url: response.url() });
          return;
        }
        if (this.consumeDeclared(request.method(), url.path, status, scenario)) return;
        this.events.push({ scenario, kind: "http", detail: { method: request.method(), path: url.path, status } });
      });
      page.on("requestfailed", (request) => {
        const url = sanitizeUrl(request.url());
        evidence.network({
          at: new Date().toISOString(),
          scenario: this.options.currentScenario(),
          role,
          method: request.method(),
          origin: url.origin,
          path: url.path,
          queryKeys: url.queryKeys,
          failure: request.failure()?.errorText ?? "unknown",
        });
      });
    };

    this.context.pages().forEach(watchPage);
    this.context.on("page", watchPage);
  }

  /** Console "Failed to load resource" lines for a declared failure: the response handler consumes the declaration. */
  private declaredConsoleMatch(locationUrl: string, status: number, scenario: string): boolean {
    let pathname: string;
    try {
      pathname = new URL(locationUrl).pathname;
    } catch {
      return false;
    }
    return this.declared.some((d) => d.scenario === scenario && d.pathname === pathname && d.status === status);
  }

  /** Asserts the common oracles for one scenario and records them in the ledger. */
  assertClean(scenario: string): void {
    const mine = this.events.filter((e) => e.scenario === scenario);
    const count = (kind: MonitorEvent["kind"]) => mine.filter((e) => e.kind === kind);
    const { evidence, role } = this.options;
    const httpNoiseUrls = new Set(count("noise").filter((e) => e.detail.source === "http").map((e) => e.url));
    const uncorroborated = count("noise").filter((e) => e.detail.source === "console" && !httpNoiseUrls.has(e.url));
    const noise = count("noise").length - uncorroborated.length;
    // Every oracle is recorded before any failure is raised, so one failing oracle cannot
    // hide the outcome of the others.
    const unusedDeclared = this.declared.filter((d) => d.scenario === scenario && !d.used);
    const results: Array<[string, boolean]> = [
      [`${role}: zero page errors`, evidence.record(scenario, `${role}: zero page errors`, count("pageerror").length === 0, {
        pageErrors: count("pageerror").map((e) => e.detail),
      })],
      [`${role}: zero unexpected console errors`, evidence.record(scenario, `${role}: zero unexpected console errors`, count("console").length + uncorroborated.length === 0, {
        consoleErrors: count("console").map((e) => e.detail),
        uncorroboratedNoise: uncorroborated.length,
        knownNoise: noise,
      })],
      [`${role}: every auth request used a run identity`, evidence.record(scenario, `${role}: every auth request used a run identity (never the demo account)`, count("identity").length === 0, {
        blocked: count("identity").map((e) => e.detail),
      })],
      [`${role}: zero unexpected HTTP >= 400`, evidence.record(scenario, `${role}: zero unexpected HTTP >= 400`, count("http").length === 0, {
        httpFailures: count("http").map((e) => e.detail),
      })],
      [`${role}: zero non-target-origin requests`, evidence.record(scenario, `${role}: zero non-target-origin requests`, count("non-target").length === 0, {
        nonTarget: count("non-target").map((e) => e.detail),
      })],
      [`${role}: every declared failure was observed`, evidence.record(scenario, `${role}: every declared failure was observed`, unusedDeclared.length === 0, {
        unobserved: unusedDeclared.map(({ method, pathname, status }) => ({ method, pathname, status })),
      })],
    ];
    const failing = results.filter(([, pass]) => !pass).map(([check]) => check);
    expect(failing, `${scenario}: common oracles failed`).toEqual([]);
  }
}
