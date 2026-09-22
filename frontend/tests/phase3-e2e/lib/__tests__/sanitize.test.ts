import { describe, expect, it } from "vitest";
import { SecretRegistry, sanitizeUrl } from "../sanitize";

describe("sanitizeUrl", () => {
  it("keeps the origin and path but only the sorted query keys", () => {
    expect(sanitizeUrl("https://api.example.dev/api/market/prices?tickers=AAPL,MSFT&b=2")).toEqual({
      origin: "https://api.example.dev",
      path: "/api/market/prices",
      queryKeys: ["b", "tickers"],
    });
  });

  it("drops userinfo and fragments", () => {
    expect(sanitizeUrl("https://user:pw@api.example.dev/x#frag")).toEqual({
      origin: "https://api.example.dev",
      path: "/x",
      queryKeys: [],
    });
  });
});

describe("SecretRegistry.assertClean", () => {
  it("rejects a registered secret anywhere in a nested value", () => {
    const registry = new SecretRegistry();
    registry.register("s3cret-password-value");
    expect(() => registry.assertClean({ a: [{ b: "x s3cret-password-value y" }] })).toThrow(/registered secret/);
  });

  it("rejects anything shaped like a JWT", () => {
    const registry = new SecretRegistry();
    expect(() => registry.assertClean({ note: "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig" })).toThrow(/JWT/);
  });

  it("rejects email addresses", () => {
    const registry = new SecretRegistry();
    expect(() => registry.assertClean(["p3-fresh-1@example.test"])).toThrow(/email/);
  });

  it("accepts ordinary structured evidence", () => {
    const registry = new SecretRegistry();
    registry.register("s3cret-password-value");
    expect(() =>
      registry.assertClean({
        scenario: "S05",
        user: "FRESH",
        holdings: [{ ticker: "GOOGL", quantity: "7" }],
        version: 1,
        ok: true,
        missing: null,
      }),
    ).not.toThrow();
  });

  it("ignores registrations too short to be meaningful secrets", () => {
    const registry = new SecretRegistry();
    registry.register("ab");
    expect(() => registry.assertClean({ text: "abc" })).not.toThrow();
  });
});
