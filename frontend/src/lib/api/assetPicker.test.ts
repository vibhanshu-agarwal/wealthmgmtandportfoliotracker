/**
 * B2 Task 1.7 (Browse) / Task 1.11 (catalog conditional revalidation, extended in
 * Checkpoint 3) — the catalog fetch the browse list is built on.
 */
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { fetchCatalog } from "./assetPicker";

const TOKEN = "test-token";

describe("fetchCatalog", () => {
  it("returns the catalog's assets", async () => {
    server.use(
      http.get("/api/assets", () =>
        HttpResponse.json({
          catalogVersion: "v1",
          assets: [
            {
              ticker: "AAPL",
              name: "Apple Inc.",
              aliases: ["Apple"],
              assetClass: "STOCK",
              quoteCurrency: "USD",
              lifecycleStatus: "ACTIVE",
            },
          ],
        }),
      ),
    );

    const result = await fetchCatalog(TOKEN);

    expect(result.assets).toHaveLength(1);
    expect(result.assets[0].ticker).toBe("AAPL");
    expect(result.catalogVersion).toBe("v1");
  });
});

describe("fetchCatalog — conditional revalidation (Task 1.11)", () => {
  it("sends the previously-observed ETag as If-None-Match on the next request", async () => {
    let receivedIfNoneMatch: string | null = null;
    let requestCount = 0;

    server.use(
      http.get("/api/assets", ({ request }) => {
        requestCount += 1;
        if (requestCount === 1) {
          return HttpResponse.json(
            { catalogVersion: "v1", assets: [{ ticker: "AAPL", name: "Apple", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" }] },
            { headers: { ETag: '"v1"' } },
          );
        }
        receivedIfNoneMatch = request.headers.get("If-None-Match");
        return new HttpResponse(null, { status: 304, headers: { ETag: '"v1"' } });
      }),
    );

    await fetchCatalog(TOKEN);
    const second = await fetchCatalog(TOKEN);

    expect(receivedIfNoneMatch).toBe('"v1"');
    // 304 → no body — the client reuses its already-held catalog, not an empty one.
    expect(second.assets).toHaveLength(1);
    expect(second.assets[0].ticker).toBe("AAPL");
  });

  it("adopts a fresh 200 response and its new ETag for the following request", async () => {
    let requestCount = 0;
    const ifNoneMatchSeen: Array<string | null> = [];

    server.use(
      http.get("/api/assets", ({ request }) => {
        requestCount += 1;
        ifNoneMatchSeen.push(request.headers.get("If-None-Match"));
        if (requestCount === 1) {
          return HttpResponse.json(
            { catalogVersion: "v1", assets: [] },
            { headers: { ETag: '"v1"' } },
          );
        }
        return HttpResponse.json(
          { catalogVersion: "v2", assets: [{ ticker: "BTC", name: "Bitcoin", aliases: [], assetClass: "CRYPTO", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" }] },
          { headers: { ETag: '"v2"' } },
        );
      }),
    );

    await fetchCatalog(TOKEN);
    const second = await fetchCatalog(TOKEN);

    expect(second.catalogVersion).toBe("v2");
    expect(ifNoneMatchSeen[1]).toBe('"v1"');
  });
});
