/**
 * B2 Task 1.13 — the composition-save mutation's network call.
 *
 * Mocks B1's frozen `PUT /api/portfolio/holdings` contract (design.md D2): a `200`
 * carries the full `PortfolioResponse`; a `409` carries
 * `{ error: "portfolio_version_conflict", message, currentVersion }`.
 */
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { saveComposition } from "./assetPickerSave";

const TOKEN = "test-token";

describe("saveComposition", () => {
  it("sends the exact payload as the PUT body", async () => {
    let receivedBody: unknown = null;
    server.use(
      http.put("/api/portfolio/holdings", async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
        });
      }),
    );

    await saveComposition(TOKEN, { expectedVersion: 7, holdings: [{ ticker: "AAPL", quantity: "10" }] });

    expect(receivedBody).toEqual({ expectedVersion: 7, holdings: [{ ticker: "AAPL", quantity: "10" }] });
  });

  it("returns a success result carrying the response's holdings and version on 200", async () => {
    server.use(
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
        }),
      ),
    );

    const result = await saveComposition(TOKEN, { expectedVersion: 7, holdings: [] });

    expect(result).toEqual({
      status: "success",
      version: 8,
      holdings: [{ assetTicker: "AAPL", quantity: "10" }],
    });
  });

  it("returns a conflict result carrying currentVersion on 409, never throwing", async () => {
    server.use(
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else saved a different version.",
            currentVersion: 9,
          },
          { status: 409 },
        ),
      ),
    );

    const result = await saveComposition(TOKEN, { expectedVersion: 7, holdings: [] });

    expect(result).toEqual({
      status: "conflict",
      currentVersion: 9,
      message: "Someone else saved a different version.",
    });
  });

  it("throws on an unexpected status, distinct from the handled conflict case", async () => {
    server.use(
      http.put("/api/portfolio/holdings", () => new HttpResponse(null, { status: 500 })),
    );

    await expect(
      saveComposition(TOKEN, { expectedVersion: 7, holdings: [] }),
    ).rejects.toThrow();
  });
});
