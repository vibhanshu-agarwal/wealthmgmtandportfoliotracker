/**
 * B2 Task 6.2 — the manual demo-reset network call.
 *
 * Mocks the gateway's `PUT /api/portfolio/demo-reset` route (design.md D5): a `200`
 * carries the fresh `PortfolioResponse`, same shape as a successful composition save;
 * a `409` carries B1's `{ error: "portfolio_version_conflict", message, currentVersion }`
 * envelope. GC.6: the request body carries only the browser's already-observed
 * `expectedVersion` — never a version this adapter re-reads or defaults itself.
 */
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { demoReset } from "./demoReset";

const TOKEN = "test-token";

describe("demoReset", () => {
  it("sends only expectedVersion as the PUT body", async () => {
    let receivedBody: unknown = null;
    server.use(
      http.put("/api/portfolio/demo-reset", async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    await demoReset(TOKEN, 7);

    expect(receivedBody).toEqual({ expectedVersion: 7 });
  });

  it("sends the exact Authorization bearer token and no body fields beyond expectedVersion, even for version zero", async () => {
    let receivedBody: unknown = null;
    let receivedAuth: string | null = null;
    server.use(
      http.put("/api/portfolio/demo-reset", async ({ request }) => {
        receivedBody = await request.json();
        receivedAuth = request.headers.get("Authorization");
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    // A real observed version zero is valid (requirements.md 7.3) — must be sent
    // verbatim, never treated as falsy/missing and coerced to something else.
    await demoReset(TOKEN, 0);

    expect(receivedBody).toEqual({ expectedVersion: 0 });
    expect(receivedAuth).toBe(`Bearer ${TOKEN}`);
  });

  it("issues exactly one PUT request per call", async () => {
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/demo-reset", () => {
        putCount += 1;
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    await demoReset(TOKEN, 3);

    expect(putCount).toBe(1);
  });

  it("returns a success result carrying the response's holdings and version on 200", async () => {
    server.use(
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
        }),
      ),
    );

    const result = await demoReset(TOKEN, 0);

    expect(result).toEqual({
      status: "success",
      portfolioId: "p1",
      ownerId: "user-001",
      version: 1,
      holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
    });
  });

  it("returns a conflict result carrying currentVersion on 409, never throwing", async () => {
    server.use(
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else changed the demo portfolio.",
            currentVersion: 5,
          },
          { status: 409 },
        ),
      ),
    );

    const result = await demoReset(TOKEN, 3);

    expect(result).toEqual({
      status: "conflict",
      currentVersion: 5,
      message: "Someone else changed the demo portfolio.",
    });
  });

  it.each([401, 403, 429, 503])(
    "throws a usable error on a %i response, distinct from the handled conflict case",
    async (status) => {
      server.use(
        http.put("/api/portfolio/demo-reset", () => new HttpResponse(null, { status })),
      );

      await expect(demoReset(TOKEN, 3)).rejects.toThrow();
    },
  );

  it("throws on a network failure", async () => {
    server.use(http.put("/api/portfolio/demo-reset", () => HttpResponse.error()));

    await expect(demoReset(TOKEN, 3)).rejects.toThrow();
  });
});
