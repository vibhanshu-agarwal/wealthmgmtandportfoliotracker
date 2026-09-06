import { describe, expect, it } from "vitest";
import {
  assertExactPersistedHoldings,
  assertVersionAdvanced,
  chooseKnownDifferentHoldings,
  selectExactPortfolio,
} from "../asset-picker-real";

const E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

const portfolio = {
  id: "portfolio-e2e",
  userId: E2E_USER_ID,
  version: 7,
  holdings: [
    { assetTicker: "AAPL", quantity: "10" },
    { assetTicker: "BTC-USD", quantity: "2" },
  ],
};

describe("real asset-picker E2E oracles", () => {
  it("selects exactly one identity-matched portfolio and preserves its observed version", () => {
    expect(
      selectExactPortfolio(
        [{ ...portfolio, userId: "another-user", version: 99 }, portfolio],
        E2E_USER_ID,
      ),
    ).toEqual({
      id: "portfolio-e2e",
      userId: E2E_USER_ID,
      version: 7,
      holdings: [
        { ticker: "AAPL", quantity: "10" },
        { ticker: "BTC-USD", quantity: "2" },
      ],
    });
  });

  it.each([
    ["zero", []],
    ["multiple", [portfolio, { ...portfolio, id: "portfolio-duplicate", version: 8 }]],
  ])("rejects %s identity matches instead of selecting an arbitrary portfolio", (_label, payload) => {
    expect(() => selectExactPortfolio(payload, E2E_USER_ID)).toThrow(/exactly one portfolio/);
  });

  it("rejects a setup write whose returned version did not strictly advance", () => {
    expect(() => assertVersionAdvanced("deterministic setup", 7, 7)).toThrow(/strictly advance/);
    expect(() => assertVersionAdvanced("deterministic setup", 7, 6)).toThrow(/strictly advance/);
    expect(() => assertVersionAdvanced("deterministic setup", 7, 8)).not.toThrow();
  });

  it("requires the persisted holding set to equal every ticker and quantity in the edited draft", () => {
    const expected = [
      { ticker: "AAPL", quantity: "31" },
      { ticker: "BTC-USD", quantity: "2" },
    ];

    expect(() => assertExactPersistedHoldings(expected, expected)).not.toThrow();
    expect(() =>
      assertExactPersistedHoldings(
        [
          { ticker: "AAPL", quantity: "31" },
          { ticker: "BTC-USD", quantity: "2" },
          { ticker: "GOOGL", quantity: "1" },
        ],
        expected,
      ),
    ).toThrow(/exactly/);
    expect(() =>
      assertExactPersistedHoldings(
        [
          { ticker: "AAPL", quantity: "30" },
          { ticker: "BTC-USD", quantity: "2" },
        ],
        expected,
      ),
    ).toThrow(/exactly/);
  });

  it("chooses a valid deterministic setup that differs from the observed holding set", () => {
    const selected = chooseKnownDifferentHoldings([
      { ticker: "AAPL", quantity: "17" },
      { ticker: "BTC-USD", quantity: "2" },
    ]);

    expect(selected).not.toEqual([
      { ticker: "AAPL", quantity: "17" },
      { ticker: "BTC-USD", quantity: "2" },
    ]);
    expect(selected).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ ticker: "AAPL" }),
        expect.objectContaining({ ticker: "BTC-USD" }),
      ]),
    );
  });
});
