import { render, screen, within } from "@testing-library/react";
import { vi, describe, it, expect } from "vitest";
import fc from "fast-check";
import { MarketDataPageContent } from "./MarketDataPageContent";
import type { AssetHoldingDTO, AssetClass } from "@/types/portfolio";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
}));

vi.mock("@/lib/auth-client", () => ({
  useSession: () => ({
    data: { user: { id: "u1", name: "Test", email: "t@t.com" } },
    isPending: false,
  }),
}));

const mockUsePortfolio = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  usePortfolio: () => mockUsePortfolio(),
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

const arbHolding: fc.Arbitrary<AssetHoldingDTO> = fc.record({
  id: fc.uuid(),
  ticker: fc.stringMatching(/^[A-Z]{1,5}$/),
  name: fc.string({ minLength: 1, maxLength: 30 }),
  assetClass: arbAssetClass,
  quantity: fc.double({ min: 0.001, max: 100000, noNaN: true }),
  currentPrice: fc.double({ min: 0.01, max: 999999, noNaN: true }),
  totalValue: fc.double({ min: 0, max: 999999999, noNaN: true }),
  avgCostBasis: fc.double({ min: 0.01, max: 999999, noNaN: true }),
  unrealizedPnL: fc.double({ min: -999999, max: 999999, noNaN: true }),
  change24hPercent: fc.double({ min: -99, max: 99, noNaN: true }),
  change24hAbsolute: fc.double({ min: -99999, max: 99999, noNaN: true }),
  portfolioWeight: fc.double({ min: 0, max: 100, noNaN: true }),
  lastUpdatedAt: fc
    .integer({ min: 1577836800000, max: 1924905600000 }) // 2020-01-01 to 2030-12-31
    .map((ts) => new Date(ts).toISOString()),
});

const arbHoldings = fc.array(arbHolding, { minLength: 1, maxLength: 50 });

// ── Property Tests ────────────────────────────────────────────────────────────

describe("MarketDataPageContent — Property-Based Tests", () => {
  /**
   * Property 1: Holdings-to-rows data integrity
   *
   * For any array of AssetHoldingDTO objects, the table SHALL render exactly
   * one row per holding, and each row SHALL contain the holding's ticker and
   * formatted currentPrice.
   *
   * Tag: Feature: ui-polish-overview-market-data, Property 1: Holdings-to-rows data integrity
   * Validates: Requirements 5.2
   */
  it("renders exactly one table row per holding with correct ticker and price", () => {
    fc.assert(
      fc.property(arbHoldings, (holdings) => {
        mockUsePortfolio.mockReturnValue({
          data: {
            portfolioId: "p1",
            ownerId: "u1",
            name: "Test",
            currency: "USD",
            summary: {},
            holdings,
            asOfDate: new Date().toISOString(),
          },
          isLoading: false,
          isError: false,
        });

        const { unmount } = render(<MarketDataPageContent />);

        const tbody = screen.getByRole("table").querySelector("tbody")!;
        const rows = within(tbody).getAllByRole("row");

        // Exactly one row per holding
        expect(rows).toHaveLength(holdings.length);

        // Each row contains the ticker and formatted price
        holdings.forEach((h, i) => {
          const row = rows[i];
          expect(row.textContent).toContain(h.ticker);
          expect(row.textContent).toContain(formatCurrency(h.currentPrice));
        });

        unmount();
      }),
      { numRuns: 100 },
    );
  });

  /**
   * Property 2: Change indicator color correctness
   *
   * For any AssetHoldingDTO, the 24h change cell SHALL apply green styling
   * when change24hPercent >= 0 and red styling when change24hPercent < 0.
   *
   * Tag: Feature: ui-polish-overview-market-data, Property 2: Change indicator color correctness
   * Validates: Requirements 5.3, 5.4
   */
  it("applies correct color class based on change24hPercent sign", () => {
    fc.assert(
      fc.property(arbHolding, (holding) => {
        mockUsePortfolio.mockReturnValue({
          data: {
            portfolioId: "p1",
            ownerId: "u1",
            name: "Test",
            currency: "USD",
            summary: {},
            holdings: [holding],
            asOfDate: new Date().toISOString(),
          },
          isLoading: false,
          isError: false,
        });

        const { unmount } = render(<MarketDataPageContent />);

        const tbody = screen.getByRole("table").querySelector("tbody")!;
        const row = within(tbody).getAllByRole("row")[0];

        // The change cell contains the formatted percent
        const formattedPercent = formatPercent(holding.change24hPercent);
        const changeCell = Array.from(row.querySelectorAll("td")).find((td) =>
          td.textContent?.includes(formattedPercent),
        );

        expect(changeCell).toBeDefined();

        if (holding.change24hPercent >= 0) {
          expect(changeCell!.className).toContain("text-green-600");
          expect(changeCell!.className).not.toContain("text-red-600");
        } else {
          expect(changeCell!.className).toContain("text-red-600");
          expect(changeCell!.className).not.toContain("text-green-600");
        }

        unmount();
      }),
      { numRuns: 100 },
    );
  });
});
