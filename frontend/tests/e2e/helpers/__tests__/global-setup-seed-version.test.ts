import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { server as mswServer } from "@/test/msw/server";

/**
 * Request-order / frozen-version / terminal-409 regressions for global-setup.ts.
 *
 * These tests drive an exported portfolio-seed sequence so we can prove:
 *   POST /api/auth/login
 *   GET  /api/portfolio   (exactly once, Bearer)
 *   POST /api/internal/portfolio/seed  with frozen expectedVersion
 * and that a 409 never re-observes or retries.
 *
 * MSW is closed for this file because the fixture uses a real localhost HTTP
 * server; MSW's ClientRequest interceptor would otherwise reject unhandled calls.
 */

beforeAll(() => {
  mswServer.close();
});

afterAll(() => {
  mswServer.listen({ onUnhandledRequest: "error" });
});

const E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";
const E2E_EMAIL = "e2e-test-user@vibhanshu-ai-portfolio.dev";
const E2E_PASSWORD = "e2e-test-password-2026";

type Captured = {
  method: string;
  url: string;
  authorization?: string;
  body?: unknown;
};

/** Node http-based fetch that bypasses Vitest/MSW's patched globalThis.fetch. */
function nodeHttpFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const url = new URL(typeof input === "string" ? input : input.toString());
  const method = init?.method ?? "GET";
  const headers = new Headers(init?.headers);
  const body =
    typeof init?.body === "string"
      ? init.body
      : init?.body != null
        ? String(init.body)
        : undefined;

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: `${url.pathname}${url.search}`,
        method,
        headers: Object.fromEntries(headers.entries()),
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (c: Buffer) => chunks.push(c));
        res.on("end", () => {
          const buf = Buffer.concat(chunks);
          resolve(
            new Response(buf, {
              status: res.statusCode ?? 500,
              headers: res.headers as HeadersInit,
            }),
          );
        });
      },
    );
    req.on("error", reject);
    if (body) {
      req.write(body);
    }
    req.end();
  });
}


function startFixtureServer(handlers: {
  loginStatus?: number;
  loginUserId?: string;
  loginToken?: string;
  portfolioStatus?: number;
  portfolioBody?: unknown;
  seedStatuses?: number[];
  seedBodies?: string[];
}): Promise<{
  baseUrl: string;
  captures: Captured[];
  close: () => Promise<void>;
}> {
  const captures: Captured[] = [];
  let seedIdx = 0;
  const seedStatuses = handlers.seedStatuses ?? [200];
  const seedBodies = handlers.seedBodies ?? ["{}"];

  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      let parsed: unknown;
      try {
        parsed = raw ? JSON.parse(raw) : undefined;
      } catch {
        parsed = raw;
      }
      captures.push({
        method: req.method ?? "GET",
        url: req.url ?? "",
        authorization: req.headers.authorization,
        body: parsed,
      });

      if (req.method === "POST" && req.url === "/api/auth/login") {
        const status = handlers.loginStatus ?? 200;
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            token: handlers.loginToken ?? "fixture-token",
            userId: handlers.loginUserId ?? E2E_USER_ID,
            email: E2E_EMAIL,
            name: "E2E",
          }),
        );
        return;
      }

      if (req.method === "GET" && req.url === "/api/portfolio") {
        const status = handlers.portfolioStatus ?? 200;
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify(
            handlers.portfolioBody ?? [{ userId: E2E_USER_ID, version: 7 }],
          ),
        );
        return;
      }

      if (req.method === "POST" && req.url === "/api/internal/portfolio/seed") {
        const status = seedStatuses[Math.min(seedIdx, seedStatuses.length - 1)]!;
        const body = seedBodies[Math.min(seedIdx, seedBodies.length - 1)]!;
        seedIdx += 1;
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(body);
        return;
      }

      res.writeHead(404);
      res.end("not found");
    });
  });

  return new Promise((resolve, reject) => {
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address() as AddressInfo;
      resolve({
        baseUrl: `http://127.0.0.1:${port}`,
        captures,
        close: () =>
          new Promise((resClose, rejClose) => {
            server.close((err) => (err ? rejClose(err) : resClose()));
          }),
      });
    });
    server.on("error", reject);
  });
}

describe("global-setup frozen portfolio seed sequence", () => {
  let closeServer: (() => Promise<void>) | undefined;

  afterEach(async () => {
    if (closeServer) {
      await closeServer();
      closeServer = undefined;
    }
    vi.restoreAllMocks();
  });

  it("logs in, reads portfolio once, then seeds with the frozen expectedVersion", async () => {
    const { seedPortfolioWithFrozenVersion } = await import("../../global-setup");
    const fixture = await startFixtureServer({
      portfolioBody: [{ userId: E2E_USER_ID, version: 7 }],
      seedStatuses: [200],
    });
    closeServer = fixture.close;

    const logs: string[] = [];
    const logSpy = vi.spyOn(console, "log").mockImplementation((...args) => {
      logs.push(args.map(String).join(" "));
    });

    await seedPortfolioWithFrozenVersion({
      apiBase: fixture.baseUrl,
      internalApiKey: "test-internal-key",
      email: E2E_EMAIL,
      password: E2E_PASSWORD,
      expectedUserId: E2E_USER_ID,
      maxAttempts: 3,
      sleepFn: async () => undefined,
      fetchFn: fetch,
    });

    logSpy.mockRestore();

    const methods = fixture.captures.map((c) => `${c.method} ${c.url}`);
    expect(methods).toEqual([
      "POST /api/auth/login",
      "GET /api/portfolio",
      "POST /api/internal/portfolio/seed",
    ]);
    expect(fixture.captures[1]?.authorization).toBe("Bearer fixture-token");
    expect(fixture.captures[2]?.body).toEqual({ expectedVersion: 7 });
    expect(logs.some((line) => line.includes("[b1-g5][global-setup] expectedVersion=7"))).toBe(
      true,
    );
  });

  it("does not re-read portfolio inside a transient seed retry; reuses frozen body", async () => {
    const { seedPortfolioWithFrozenVersion } = await import("../../global-setup");
    const fixture = await startFixtureServer({
      portfolioBody: [{ userId: E2E_USER_ID, version: 4 }],
      seedStatuses: [503, 200],
      seedBodies: ["cold", "{}"],
    });
    closeServer = fixture.close;

    await seedPortfolioWithFrozenVersion({
      apiBase: fixture.baseUrl,
      internalApiKey: "test-internal-key",
      email: E2E_EMAIL,
      password: E2E_PASSWORD,
      expectedUserId: E2E_USER_ID,
      maxAttempts: 3,
      sleepFn: async () => undefined,
      fetchFn: fetch,
    });

    const portfolioGets = fixture.captures.filter(
      (c) => c.method === "GET" && c.url === "/api/portfolio",
    );
    const seedPosts = fixture.captures.filter(
      (c) => c.method === "POST" && c.url === "/api/internal/portfolio/seed",
    );
    expect(portfolioGets).toHaveLength(1);
    expect(seedPosts).toHaveLength(2);
    expect(seedPosts[0]?.body).toEqual({ expectedVersion: 4 });
    expect(seedPosts[1]?.body).toEqual({ expectedVersion: 4 });
  });

  it("treats 409 as terminal on attempt 1 with body, without re-observation", async () => {
    const { seedPortfolioWithFrozenVersion } = await import("../../global-setup");
    const conflictBody = JSON.stringify({
      error: "portfolio_version_conflict",
      currentVersion: 9,
    });
    const fixture = await startFixtureServer({
      portfolioBody: [{ userId: E2E_USER_ID, version: 3 }],
      seedStatuses: [409, 200],
      seedBodies: [conflictBody, "{}"],
    });
    closeServer = fixture.close;

    await expect(
      seedPortfolioWithFrozenVersion({
        apiBase: fixture.baseUrl,
        internalApiKey: "test-internal-key",
        email: E2E_EMAIL,
        password: E2E_PASSWORD,
        expectedUserId: E2E_USER_ID,
        maxAttempts: 5,
        sleepFn: async () => undefined,
        fetchFn: fetch,
      }),
    ).rejects.toThrow(
      /attempt 1[\s\S]*portfolio_version_conflict[\s\S]*currentVersion|portfolio_version_conflict[\s\S]*currentVersion[\s\S]*attempt 1/i,
    );


    const portfolioGets = fixture.captures.filter(
      (c) => c.method === "GET" && c.url === "/api/portfolio",
    );
    const seedPosts = fixture.captures.filter(
      (c) => c.method === "POST" && c.url === "/api/internal/portfolio/seed",
    );
    expect(portfolioGets).toHaveLength(1);
    expect(seedPosts).toHaveLength(1);
  });
});
