import { render, screen, waitFor, within } from "@testing-library/react";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { AllocationChart } from "./AllocationChart";
import type { AllocationSliceDTO, AssetAllocationDTO } from "@/types/portfolio";

// The real Recharts ResponsiveContainer is used on purpose: it renders its chart one pass
// later, after measuring, which is the window in which a legend that mutates the shared
// slice array can reorder the donut's data under its already-created <Cell> fills.
// This stub reports a fixed box as soon as the container is observed.
const OriginalResizeObserver = global.ResizeObserver;
beforeAll(() => {
  global.ResizeObserver = class {
    private readonly callback: ResizeObserverCallback;
    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }
    observe() {
      this.callback(
        [{ contentRect: { width: 400, height: 180 } } as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    }
    unobserve() {}
    disconnect() {}
  };
});
afterAll(() => {
  global.ResizeObserver = OriginalResizeObserver;
});

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockUseAssetAllocationFromAnalytics = vi.fn();
vi.mock("@/lib/hooks/usePortfolio", () => ({
  useAssetAllocationFromAnalytics: () => mockUseAssetAllocationFromAnalytics(),
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

// Deliberately NOT in descending-percentage order, so any in-place sort is observable.
const SLICES: readonly AllocationSliceDTO[] = [
  { assetClass: "STOCK", label: "Stocks", value: 500, percentage: 5.0, color: "#10b981" },
  { assetClass: "CRYPTO", label: "Crypto", value: 9260, percentage: 92.6, color: "#f97316" },
  { assetClass: "CASH", label: "Cash", value: 240, percentage: 2.4, color: "#64748b" },
];

const EXPECTED_COLOUR: Record<string, string> = Object.fromEntries(
  SLICES.map((s) => [s.label, s.color]),
);

/** The hook's `select` builds a fresh DTO on every render; mirror that and keep the last one. */
let lastReturned: AssetAllocationDTO | undefined;
function freshAllocation(): AssetAllocationDTO {
  return {
    portfolioId: "p1",
    totalValue: 10000,
    slices: SLICES.map((s) => ({ ...s })),
  } as AssetAllocationDTO;
}

describe("AllocationChart — colour correctness", () => {
  beforeEach(() => {
    lastReturned = undefined;
    mockUseAssetAllocationFromAnalytics.mockImplementation(() => {
      lastReturned = freshAllocation();
      return { data: lastReturned, isLoading: false };
    });
  });

  it("does not reorder the slice array it is given when rendering the legend", () => {
    render(<AllocationChart />);

    expect(lastReturned).toBeDefined();
    expect(lastReturned!.slices.map((s) => s.label)).toEqual(
      SLICES.map((s) => s.label),
    );
  });

  it("lists the legend in descending percentage order", () => {
    render(<AllocationChart />);

    const items = within(screen.getByRole("list")).getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual([
      "Crypto92.6%",
      "Stocks5.0%",
      "Cash2.4%",
    ]);
  });

  it("paints each legend swatch with its own asset class colour", () => {
    render(<AllocationChart />);

    const items = within(screen.getByRole("list")).getAllByRole("listitem");
    for (const li of items) {
      const label = Object.keys(EXPECTED_COLOUR).find((l) => li.textContent?.startsWith(l));
      expect(label, `unexpected legend row ${li.textContent}`).toBeDefined();
      const swatch = li.querySelector<HTMLElement>("span.rounded-full");
      expect(swatch).not.toBeNull();
      expect(swatch!.style.backgroundColor).toBe(hexToRgb(EXPECTED_COLOUR[label!]));
    }
  });

  it("paints each donut sector with the colour of the asset class it represents", async () => {
    const { container } = render(<AllocationChart />);

    // Pie sectors animate in from zero sweep, so wait until every slice is drawn.
    await waitFor(
      () => {
        expect(container.querySelectorAll("path.recharts-sector")).toHaveLength(SLICES.length);
      },
      { timeout: 4000 },
    );

    const sectors = Array.from(container.querySelectorAll<SVGPathElement>("path.recharts-sector"));
    const byLabel = Object.fromEntries(
      sectors.map((p) => [p.getAttribute("name"), p.getAttribute("fill")]),
    );
    expect(byLabel).toEqual(EXPECTED_COLOUR);
  });
});

function hexToRgb(hex: string): string {
  const n = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}
