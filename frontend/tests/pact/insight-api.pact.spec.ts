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
        builder.jsonBody({
          AAPL: like({
            ticker: like("AAPL"),
            latestPrice: like(178.5),
            priceHistory: eachLike(175.0),
            trendPercent: like(2.0),
            aiSummary: like("AAPL is Bullish. Prices are rising steadily."),
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
        expect(ticker).toHaveProperty("aiSummary");
      });
  });
});
