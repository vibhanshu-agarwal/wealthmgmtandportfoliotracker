// @vitest-environment node
import { PactV4, MatchersV3 } from "@pact-foundation/pact";
import { describe, it, expect } from "vitest";

const { like, eachLike } = MatchersV3;

const provider = new PactV4({
  consumer: "wealth-frontend",
  provider: "portfolio-service",
  dir: "./pacts",
});

describe("Portfolio API consumer contract", () => {
  it("returns a list of portfolios for GET /api/portfolio", async () => {
    await provider
      .addInteraction()
      .given("user has a portfolio with holdings")
      .uponReceiving("a request for user portfolios")
      .withRequest("GET", "/api/portfolio", (builder) => {
        builder.headers({ "X-User-Id": like("user-001") });
      })
      .willRespondWith(200, (builder) => {
        builder.headers({ "Content-Type": "application/json" });
        builder.jsonBody(
          eachLike({
            id: like("portfolio-001"),
            userId: like("user-001"),
            createdAt: like("2026-04-15T10:30:00Z"),
            holdings: eachLike({
              id: like("holding-001"),
              assetTicker: like("AAPL"),
              quantity: like(10),
            }),
          })
        );
      })
      .executeTest(async (mockServer) => {
        const url = mockServer.url;
        const response = await fetch(`${url}/api/portfolio`, {
          method: "GET",
          headers: {
            "X-User-Id": "user-001",
          },
        });

        expect(response.status).toBe(200);

        const body = await response.json();
        expect(Array.isArray(body)).toBe(true);
        expect(body.length).toBeGreaterThan(0);

        const portfolio = body[0];
        expect(portfolio).toHaveProperty("id");
        expect(portfolio).toHaveProperty("userId");
        expect(portfolio).toHaveProperty("createdAt");
        expect(portfolio).toHaveProperty("holdings");
        expect(Array.isArray(portfolio.holdings)).toBe(true);

        const holding = portfolio.holdings[0];
        expect(holding).toHaveProperty("id");
        expect(holding).toHaveProperty("assetTicker");
        expect(holding).toHaveProperty("quantity");
      });
  });
});
