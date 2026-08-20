import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { APIRequestContext, Page } from "@playwright/test";

const E2E_EMAIL = "e2e-test-user@vibhanshu-ai-portfolio.dev";
const E2E_PASSWORD = "e2e-test-password-2026";
const E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

type LoginBehavior =
  | { kind: "ok"; userId: string }
  | { kind: "status"; status: number; body: string };

function jwtSub(authorization: string | undefined): string {
  const token = (authorization ?? "").replace(/^Bearer\s+/i, "");
  const payload = JSON.parse(
    Buffer.from(token.split(".")[1], "base64url").toString("utf8"),
  ) as { sub: string };
  return payload.sub;
}

function startLoginServer(behavior: LoginBehavior): Promise<{
  url: string;
  bodies: Array<{ email?: string; password?: string }>;
  close: () => Promise<void>;
}> {
  const bodies: Array<{ email?: string; password?: string }> = [];
  const server = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/api/auth/login") {
      const chunks: Buffer[] = [];
      req.on("data", (chunk: Buffer) => chunks.push(chunk));
      req.on("end", () => {
        try {
          bodies.push(JSON.parse(Buffer.concat(chunks).toString("utf8")) as {
            email?: string;
            password?: string;
          });
        } catch {
          bodies.push({});
        }
        if (behavior.kind === "ok") {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              token: "test-token",
              userId: behavior.userId,
              email: E2E_EMAIL,
              name: "E2E Test User",
            }),
          );
          return;
        }
        res.writeHead(behavior.status, { "Content-Type": "text/plain" });
        res.end(behavior.body);
      });
      return;
    }
    res.writeHead(404);
    res.end("not found");
  });
  return new Promise((resolve, reject) => {
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address() as AddressInfo;
      resolve({
        url: `http://127.0.0.1:${port}`,
        bodies,
        close: () =>
          new Promise((closeResolve, closeReject) => {
            server.close((err) => (err ? closeReject(err) : closeResolve()));
          }),
      });
    });
    server.on("error", reject);
  });
}

function unusedPortUrl(): string {
  return `http://127.0.0.1:1`;
}

function mockPage(): Page {
  return {
    addInitScript: vi.fn(async () => undefined),
  } as unknown as Page;
}

type CapturedRequest = {
  sub?: string;
  posts: string[];
};

function mockPortfolioRequest(
  captured: CapturedRequest,
  listBody: unknown = [
    {
      id: "p-1",
      holdings: [{ assetTicker: "AAPL" }, { assetTicker: "BTC-USD" }],
    },
  ],
): APIRequestContext {
  const jsonResponse = (status: number, body: unknown) => ({
    status: () => status,
    ok: () => status >= 200 && status < 300,
    json: async () => body,
    text: async () => JSON.stringify(body),
  });
  return {
    get: async (_url: string, opts?: { headers?: Record<string, string> }) => {
      captured.sub = jwtSub(opts?.headers?.Authorization);
      if (String(_url).includes("/api/portfolio/summary")) {
        return jsonResponse(200, { total: 0 });
      }
      return jsonResponse(200, listBody);
    },
    post: async (url: string) => {
      captured.posts.push(String(url));
      return jsonResponse(201, { id: "p-1" });
    },
  } as unknown as APIRequestContext;
}

async function loadHelpers(baseUrl: string) {
  vi.resetModules();
  process.env.NEXT_PUBLIC_API_BASE_URL = baseUrl;
  process.env.E2E_TEST_USER_EMAIL = E2E_EMAIL;
  process.env.E2E_TEST_USER_PASSWORD = E2E_PASSWORD;
  const browserAuth = await import("../browser-auth");
  const api = await import("../api");
  return { browserAuth, api };
}

beforeAll(async () => {
  const { server } = await import("@/test/msw/server");
  server.close();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("installGatewaySessionInitScript", () => {
  it("logs in with the E2E credentials, not the local-dev user", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { browserAuth } = await loadHelpers(login.url);
      await browserAuth.installGatewaySessionInitScript(
        mockPage(),
        {} as APIRequestContext,
      );
      expect(login.bodies).toEqual([{ email: E2E_EMAIL, password: E2E_PASSWORD }]);
    } finally {
      await login.close();
    }
  });

  it("resolves on a 200 login response", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { browserAuth } = await loadHelpers(login.url);
      const page = mockPage();
      await expect(
        browserAuth.installGatewaySessionInitScript(page, {} as APIRequestContext),
      ).resolves.toBeUndefined();
      expect(page.addInitScript).toHaveBeenCalledOnce();
    } finally {
      await login.close();
    }
  });

  it("throws with status and body on a 401 login response", async () => {
    const login = await startLoginServer({
      kind: "status",
      status: 401,
      body: "unauthorized-login",
    });
    try {
      const { browserAuth } = await loadHelpers(login.url);
      await expect(
        browserAuth.installGatewaySessionInitScript(mockPage(), {} as APIRequestContext),
      ).rejects.toThrow(/401[\s\S]*unauthorized-login/);
    } finally {
      await login.close();
    }
  });

  it("throws on a connection-refused login target", async () => {
    const { browserAuth } = await loadHelpers(unusedPortUrl());
    await expect(
      browserAuth.installGatewaySessionInitScript(mockPage(), {} as APIRequestContext),
    ).rejects.toThrow();
  });
});

describe("ensurePortfolioWithHoldings (E2E identity)", () => {
  it("logs in with the E2E credentials and uses that userId as the JWT sub", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: CapturedRequest = { posts: [] };
      await api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured));
      expect(login.bodies).toEqual([{ email: E2E_EMAIL, password: E2E_PASSWORD }]);
      expect(captured.sub).toBe(E2E_USER_ID);
      expect(captured.posts).toEqual([]);
    } finally {
      await login.close();
    }
  });

  it("fails the test on a 401 login instead of falling back to user-001", async () => {
    const login = await startLoginServer({
      kind: "status",
      status: 401,
      body: "nope",
    });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: CapturedRequest = { posts: [] };
      await expect(
        api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured)),
      ).rejects.toThrow(/401[\s\S]*nope/);
      expect(captured.sub).toBeUndefined();
    } finally {
      await login.close();
    }
  });

  it("fails the test on a connection-refused login instead of falling back to user-001", async () => {
    const { api } = await loadHelpers(unusedPortUrl());
    const captured: CapturedRequest = { posts: [] };
    await expect(
      api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured)),
    ).rejects.toThrow();
    expect(captured.sub).toBeUndefined();
  });
});

describe("ensurePortfolioWithHoldings (read-and-assert)", () => {
  it("fails hard when the portfolio list is empty and does not POST a repair", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: CapturedRequest = { posts: [] };
      await expect(
        api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured, [])),
      ).rejects.toThrow(/Golden-State|seeding skipped|expected a portfolio/i);
      expect(captured.posts).toEqual([]);
    } finally {
      await login.close();
    }
  });

  it("fails hard when AAPL or BTC-USD is missing and does not POST holdings", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: CapturedRequest = { posts: [] };
      await expect(
        api.ensurePortfolioWithHoldings(
          mockPortfolioRequest(captured, [
            { id: "p-1", holdings: [{ assetTicker: "AAPL" }] },
          ]),
        ),
      ).rejects.toThrow(/BTC-USD/);
      expect(captured.posts).toEqual([]);
    } finally {
      await login.close();
    }
  });

  it("returns the existing portfolio id when Golden-State holdings are present", async () => {
    const login = await startLoginServer({ kind: "ok", userId: E2E_USER_ID });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: CapturedRequest = { posts: [] };
      await expect(
        api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured)),
      ).resolves.toBe("p-1");
      expect(captured.posts).toEqual([]);
    } finally {
      await login.close();
    }
  });
});
