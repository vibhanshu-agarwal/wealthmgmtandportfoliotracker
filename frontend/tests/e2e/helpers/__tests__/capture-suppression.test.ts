import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { APIRequestContext, Page } from "@playwright/test";

type LoginBehavior =
  | { kind: "ok"; userId: string }
  | { kind: "status"; status: number; body: string }
  | { kind: "down" };

function jwtSub(authorization: string | undefined): string {
  const token = (authorization ?? "").replace(/^Bearer\s+/i, "");
  const payload = JSON.parse(
    Buffer.from(token.split(".")[1], "base64url").toString("utf8"),
  ) as { sub: string };
  return payload.sub;
}

function startLoginServer(behavior: Exclude<LoginBehavior, { kind: "down" }>): Promise<{
  url: string;
  close: () => Promise<void>;
}> {
  const server = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/api/auth/login") {
      if (behavior.kind === "ok") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            token: "test-token",
            userId: behavior.userId,
            email: "dev@local",
            name: "Dev User",
          }),
        );
        return;
      }
      res.writeHead(behavior.status, { "Content-Type": "text/plain" });
      res.end(behavior.body);
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

function mockPortfolioRequest(captured: { sub?: string }): APIRequestContext {
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
      return jsonResponse(200, [
        {
          id: "p-1",
          holdings: [{ assetTicker: "AAPL" }, { assetTicker: "BTC" }],
        },
      ]);
    },
    post: async () => jsonResponse(201, { id: "p-1" }),
  } as unknown as APIRequestContext;
}

async function loadHelpers(baseUrl: string) {
  vi.resetModules();
  process.env.NEXT_PUBLIC_API_BASE_URL = baseUrl;
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
  it("resolves on a 200 login response", async () => {
    const login = await startLoginServer({ kind: "ok", userId: "user-live" });
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

describe("ensurePortfolioWithHoldings (resolveUserId)", () => {
  it("uses the login userId as the JWT sub on a 200-with-userId response", async () => {
    const login = await startLoginServer({ kind: "ok", userId: "user-live" });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: { sub?: string } = {};
      await api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured));
      expect(captured.sub).toBe("user-live");
    } finally {
      await login.close();
    }
  });

  it("falls back to user-001 on a 401 login response without throwing", async () => {
    const login = await startLoginServer({
      kind: "status",
      status: 401,
      body: "nope",
    });
    try {
      const { api } = await loadHelpers(login.url);
      const captured: { sub?: string } = {};
      await expect(
        api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured)),
      ).resolves.toBe("p-1");
      expect(captured.sub).toBe("user-001");
    } finally {
      await login.close();
    }
  });

  it("falls back to user-001 on a connection-refused login target without throwing", async () => {
    const { api } = await loadHelpers(unusedPortUrl());
    const captured: { sub?: string } = {};
    await expect(
      api.ensurePortfolioWithHoldings(mockPortfolioRequest(captured)),
    ).resolves.toBe("p-1");
    expect(captured.sub).toBe("user-001");
  });
});
