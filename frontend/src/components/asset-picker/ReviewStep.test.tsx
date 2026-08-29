/**
 * B2 Task 1.12 — `ReviewStep`: renders `diffHoldings`'s four buckets, with
 * `aria-current="step"` on the step indicator and an `aria-live="polite"` draft-count
 * summary.
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { DraftHoldings } from "@/types/assetPicker";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { ReviewStep } from "./ReviewStep";

function holding(overrides: Partial<AssetHoldingDTO> = {}): AssetHoldingDTO {
  return {
    id: "h1",
    ticker: "AAPL",
    name: "Apple Inc.",
    assetClass: "STOCK",
    quantity: "10",
    currentPrice: 100,
    totalValue: 1000,
    avgCostBasis: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hPercent: null,
    change24hAbsolute: null,
    portfolioWeight: 100,
    lastUpdatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function draftEntry(ticker: string, quantity: string) {
  return {
    ticker,
    name: ticker,
    assetClass: "STOCK",
    quantity,
    source: "held" as const,
    lifecycleStatus: "ACTIVE" as const,
  };
}

describe("ReviewStep", () => {
  it("groups holdings into added/changed/removed/unchanged", () => {
    const draft: DraftHoldings = new Map([
      ["AAPL", draftEntry("AAPL", "10")],
      ["GOOGL", draftEntry("GOOGL", "3")],
    ]);
    render(
      <ReviewStep
        initialHoldings={[holding({ ticker: "AAPL", quantity: "10" })]}
        draft={draft}
        onBack={vi.fn()}
        onSave={vi.fn()}
        saving={false}
      />,
    );

    expect(screen.getByText(/added/i)).toBeInTheDocument();
    expect(screen.getByText("GOOGL")).toBeInTheDocument();
  });

  it("announces the draft count as a polite live region", () => {
    const draft: DraftHoldings = new Map([["AAPL", draftEntry("AAPL", "10")]]);
    render(
      <ReviewStep
        initialHoldings={[]}
        draft={draft}
        onBack={vi.fn()}
        onSave={vi.fn()}
        saving={false}
      />,
    );

    const status = screen.getByText(/1 in draft/i);
    expect(status).toHaveAttribute("aria-live", "polite");
  });

  it("calls onSave with the built payload when Save is activated", () => {
    const onSave = vi.fn();
    const draft: DraftHoldings = new Map([["AAPL", draftEntry("AAPL", "10")]]);
    render(
      <ReviewStep
        initialHoldings={[]}
        draft={draft}
        onBack={vi.fn()}
        onSave={onSave}
        saving={false}
      />,
    );

    screen.getByRole("button", { name: /save/i }).click();
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("disables Save while a save is in flight", () => {
    render(
      <ReviewStep
        initialHoldings={[]}
        draft={new Map()}
        onBack={vi.fn()}
        onSave={vi.fn()}
        saving
      />,
    );

    expect(screen.getByRole("button", { name: /saving/i })).toBeDisabled();
  });
});
