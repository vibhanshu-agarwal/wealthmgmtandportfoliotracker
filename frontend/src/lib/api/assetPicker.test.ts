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
