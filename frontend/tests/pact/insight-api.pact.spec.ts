// @vitest-environment node
import { PactV4, MatchersV3 } from "@pact-foundation/pact";
import { describe, it, expect } from "vitest";

const { like, eachLike } = MatchersV3;

const provider = new PactV4({
  consumer: "wealth-frontend",
  provider: "insight-service",
  dir: "./pacts",
});

describe("Insight API consumer contract", () => {
  it("returns a market summary for GET /api/insights/market-summary", async () => {
    await provider
      .addInteraction()
      .given("market summary data is available")
      .uponReceiving("a request for market summary")
      .withRequest("GET", "/api/insights/market-summary")
      .willRespondWith(200, (builder) => {
        builder.headers({ "Content-Type": "application/json" });
        // aiSummary is intentionally absent from the list endpoint.
        // The list endpoint returns price/trend data only to avoid an unbounded
        // Bedrock fan-out (one call per ticker) that would exceed Lambda timeouts.
        // AI sentiment is only available on the per-ticker endpoint:
        //   GET /api/insights/market-summary/{ticker}
        builder.jsonBody({
          AAPL: like({
            ticker: like("AAPL"),
            latestPrice: like(178.5),
            priceHistory: eachLike(175.0),
            trendPercent: like(2.0),
          }),
        });
      })
      .executeTest(async (mockServer) => {
        const url = mockServer.url;
        const response = await fetch(`${url}/api/insights/market-summary`, {
          method: "GET",
        });

        expect(response.status).toBe(200);

        const body = await response.json();
        expect(typeof body).toBe("object");
        expect(body).toHaveProperty("AAPL");

        const ticker = body["AAPL"];
        expect(ticker).toHaveProperty("ticker");
        expect(ticker).toHaveProperty("latestPrice");
        expect(ticker).toHaveProperty("priceHistory");
        expect(Array.isArray(ticker.priceHistory)).toBe(true);
        expect(ticker).toHaveProperty("trendPercent");
        // aiSummary is not present on the list endpoint — use /market-summary/{ticker} for AI.
        expect(ticker).not.toHaveProperty("aiSummary");
      });
  });
});
