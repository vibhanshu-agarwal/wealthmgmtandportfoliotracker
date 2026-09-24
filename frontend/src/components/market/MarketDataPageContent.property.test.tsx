import { render, screen, within } from "@testing-library/react";
import { vi, describe, it, expect } from "vitest";
import fc from "fast-check";
import { MarketDataPageContent } from "./MarketDataPageContent";
import type {
  AssetHoldingDTO,
  AssetClass,
  HoldingAnalyticsDTO,
} from "@/types/portfolio";
import {
  formatPercent,
  formatQuotePrice,
  formatSignedCurrency,
} from "@/lib/utils/format";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

vi.mock("@/lib/auth/session", () => ({
  useAuthSession: () => ({
    data: { userId: "u1", token: "jwt-token", name: "Test", email: "t@t.com" },
    isPending: false,
  }),
}));

const mockUsePortfolio = vi.fn();
const mockUsePortfolioAnalytics = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
  usePortfolioAnalytics: () => mockUsePortfolioAnalytics(),
}));

// ── Arbitraries ───────────────────────────────────────────────────────────────

const ASSET_CLASSES: AssetClass[] = [
  "STOCK",
  "CRYPTO",
  "ETF",
  "BOND",
  "CASH",
  "COMMODITY",
];

const arbAssetClass = fc.constantFrom(...ASSET_CLASSES);

/**
 * A holding exactly as fetchPortfolio produces it: cost basis, P&L and the 24h
 * fields are always null placeholders there. The 24h values only exist in the
 * analytics response, which the page joins by ticker.
 */
const arbBaseHolding = fc.record({
  id: fc.uuid(),
  ticker: fc.stringMatching(/^[A-Z]{1,5}$/),
  name: fc.string({ minLength: 1, maxLength: 30 }),
  assetClass: arbAssetClass,
  // B2 Requirement 8.1: quantity is a plain-decimal string on the domain type.
  quantity: fc
    .double({ min: 0.001, max: 100000, noNaN: true })
    .map((value: number) => String(value)),
  // null = no market price (finding F1): the cell must read "—", never $0.00.
  currentPrice: fc.option(fc.double({ min: 0.01, max: 999999, noNaN: true }), { nil: null }),
  // Rehearsal defect #3: the price is shown in its own quote currency; a missing or
  // unrecognised currency shows the bare number, never "$".
  quoteCurrency: fc.constantFrom<string | null>("USD", "INR", "JPY", "EUR", null, "XYZ"),
  totalValue: fc.option(fc.double({ min: 0, max: 999999999, noNaN: true }), { nil: null }),
  avgCostBasis: fc.constant(null),
  unrealizedPnL: fc.constant(null),
  unrealizedPnLPercent: fc.constant(null),
  change24hPercent: fc.constant(null),
  change24hAbsolute: fc.constant(null),
  portfolioWeight: fc.double({ min: 0, max: 100, noNaN: true }),
  lastUpdatedAt: fc
    .integer({ min: 1577836800000, max: 1924905600000 })
    .map((ts: number) => new Date(ts).toISOString()),
}) as fc.Arbitrary<AssetHoldingDTO>;

/** Task 5 contract: percent and absolute are both present, or both null (no reference). */
const arbChange = fc.oneof(
  fc.record({
    percent: fc.double({ min: -99, max: 99, noNaN: true }),
    absolute: fc.double({ min: -99999, max: 99999, noNaN: true }),
  }),
  fc.constant({ percent: null, absolute: null }),
);

/** A holding plus its analytics 24h change, or undefined when analytics has no record for it. */
const arbCase = fc.record({
  holding: arbBaseHolding,
  change: fc.option(arbChange, { nil: undefined }),
});

type Case = { holding: AssetHoldingDTO; change?: { percent: number | null; absolute: number | null } };

const arbCases = fc.uniqueArray(arbCase, {
  minLength: 1,
  maxLength: 50,
  selector: (c) => c.holding.ticker,
});

function analyticsHolding(
  ticker: string,
  change: { percent: number | null; absolute: number | null },
): HoldingAnalyticsDTO {
  return {
    ticker,
    quantity: 1,
    currentPrice: 1,
    currentValueBase: 1,
    avgCostBasis: null,
    costBasisCurrency: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hAbsolute: change.absolute,
    change24hPercent: change.percent,
    change24hReferenceAt: null,
    changeBasis: null,
    quoteCurrency: "USD",
    displayAssetClass: "STOCK",
  };
}

function mockData(cases: Case[]) {
  mockUsePortfolio.mockReturnValue({
    data: {
      portfolioId: "p1",
      ownerId: "u1",
      name: "Test",
      currency: "USD",
      summary: {},
      holdings: cases.map((c) => c.holding),
      asOfDate: new Date().toISOString(),
    },
    isLoading: false,
    isError: false,
  });
  mockUsePortfolioAnalytics.mockReturnValue({
    data: {
      holdings: cases
        .filter((c) => c.change !== undefined)
        .map((c) => analyticsHolding(c.holding.ticker, c.change!)),
    },
    isLoading: false,
    isError: false,
  });
}

/** Asserts the 24h cell shows the joined analytics value, or a dash when there is none. */
function expectJoinedChange(cell: HTMLTableCellElement, c: Case) {
  const percent = c.change?.percent ?? null;
  const absolute = c.change?.absolute ?? null;

  if (percent == null) {
    expect(cell.textContent).toBe("—");
    return;
  }

  expect(cell.textContent).toContain(formatPercent(percent));
  expect(cell.textContent).toContain(formatSignedCurrency(absolute!));
  if (percent >= 0) {
    expect(cell.className).toContain("text-green-600");
    expect(cell.className).not.toContain("text-red-600");
  } else {
    expect(cell.className).toContain("text-red-600");
    expect(cell.className).not.toContain("text-green-600");
  }
}

// ── Property Tests ────────────────────────────────────────────────────────────

describe("MarketDataPageContent — Property-Based Tests", () => {
  /**
   * Property 1: Holdings-to-rows data integrity
   *
   * For any array of AssetHoldingDTO objects, the table SHALL render exactly
   * one row per holding, and each row SHALL contain the holding's ticker,
   * formatted currentPrice and the 24h change joined from analytics by ticker.
   *
   * Tag: Feature: ui-polish-overview-market-data, Property 1: Holdings-to-rows data integrity
   * Validates: Requirements 5.2
   */
  it("renders exactly one table row per holding with correct ticker, price and joined 24h change", () => {
    fc.assert(
      fc.property(arbCases, (cases: Case[]) => {
        mockData(cases);

        // Unmount in finally: a failed run must not leave its table behind for the
        // shrinking runs, or they fail on "multiple tables" instead of the real defect.
        const { unmount } = render(<MarketDataPageContent />);
        try {
          const tbody = screen.getByRole("table").querySelector("tbody")!;
          const rows = within(tbody).getAllByRole("row");

          // Exactly one row per holding
          expect(rows).toHaveLength(cases.length);

          cases.forEach((c: Case, i: number) => {
            const row = rows[i];
            expect(row.textContent).toContain(c.holding.ticker);
            const priceCell = row.querySelectorAll("td")[1] as HTMLTableCellElement;
            expect(priceCell.textContent).toBe(
              c.holding.currentPrice == null
                ? "—"
                : formatQuotePrice(c.holding.currentPrice, c.holding.quoteCurrency),
            );
            expectJoinedChange(row.querySelectorAll("td")[2] as HTMLTableCellElement, c);
          });
        } finally {
          unmount();
        }
      }),
      { numRuns: 100 },
    );
  });

  /**
   * Property 2: Change indicator color correctness
   *
   * For any holding, the 24h change cell SHALL show the analytics change for its
   * ticker, with green styling when change24hPercent >= 0 and red styling when
   * < 0, and "—" when analytics has no record or no 24h reference for it.
   *
   * Tag: Feature: ui-polish-overview-market-data, Property 2: Change indicator color correctness
   * Validates: Requirements 5.3, 5.4
   */
  it("applies correct color class based on the joined change24hPercent sign", () => {
    fc.assert(
      fc.property(arbCase, (c: Case) => {
        mockData([c]);

        const { unmount } = render(<MarketDataPageContent />);
        try {
          const tbody = screen.getByRole("table").querySelector("tbody")!;
          const row = within(tbody).getAllByRole("row")[0];
          expectJoinedChange(row.querySelectorAll("td")[2] as HTMLTableCellElement, c);
        } finally {
          unmount();
        }
      }),
      { numRuns: 100 },
    );
  });
});
