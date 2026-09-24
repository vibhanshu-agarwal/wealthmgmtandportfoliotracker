/**
 * Rehearsal defect #3: the unknown-currency fallback fails closed. Without
 * Intl.supportedValuesOf, `isKnownCurrency` used to accept any well-formed code, so "XYZ" was
 * printed as a currency. It now falls back to the catalog's own quote currencies.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

type FormatModule = typeof import("./format");

const realSupportedValuesOf = Intl.supportedValuesOf;

/** Re-imports format.ts with Intl.supportedValuesOf missing, as on an older runtime. */
async function importWithoutSupportedValuesOf(): Promise<FormatModule> {
  vi.resetModules();
  // @ts-expect-error — simulating a runtime that predates Intl.supportedValuesOf
  delete Intl.supportedValuesOf;
  return import("./format");
}

afterEach(() => {
  Intl.supportedValuesOf = realSupportedValuesOf;
  vi.resetModules();
});

describe("isKnownCurrency without Intl.supportedValuesOf", () => {
  it("rejects a well-formed but unassigned code instead of assuming it is valid", async () => {
    const { isKnownCurrency, formatQuotePrice } = await importWithoutSupportedValuesOf();

    expect(isKnownCurrency("XYZ")).toBe(false);
    expect(formatQuotePrice(1234.5, "XYZ")).toBe("1,234.50");
  });

  it("still formats every catalog quote currency", async () => {
    const { isKnownCurrency, formatQuotePrice } = await importWithoutSupportedValuesOf();

    expect(isKnownCurrency("USD")).toBe(true);
    expect(isKnownCurrency("INR")).toBe(true);
    expect(formatQuotePrice(22470, "INR")).toBe("₹22,470.00");
    expect(formatQuotePrice(337.02, "USD")).toBe("$337.02");
  });
});

describe("CATALOG_QUOTE_CURRENCIES", () => {
  it("matches the quote currencies in config/seed-tickers.json", async () => {
    const { CATALOG_QUOTE_CURRENCIES } = await import("./format");
    const catalogPath = resolve(__dirname, "../../../../config/seed-tickers.json");
    const raw: unknown = JSON.parse(readFileSync(catalogPath, "utf-8"));
    const entries = (Array.isArray(raw)
      ? raw
      : Object.values(raw as Record<string, unknown>).find(Array.isArray)) as { quoteCurrency?: string }[];

    const inCatalog = new Set(entries.map((e) => e.quoteCurrency).filter((c): c is string => !!c));
    expect(inCatalog.size).toBeGreaterThan(0);
    expect([...CATALOG_QUOTE_CURRENCIES].sort()).toEqual([...inCatalog].sort());
  });
});
