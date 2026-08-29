/**
 * B2 Task 1.14 — `ConflictPanel`: rendered alongside a read-only, keyboard-scrollable
 * draft summary, with exactly two exits (GC.4).
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { DraftHoldings } from "@/types/assetPicker";
import { ConflictPanel } from "./ConflictPanel";

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

describe("ConflictPanel", () => {
  const draft: DraftHoldings = new Map([
    ["AAPL", draftEntry("AAPL", "10")],
    ["BTC", draftEntry("BTC", "0.5")],
  ]);

  it("explains the conflict in plain language", () => {
    render(
      <ConflictPanel
        draft={draft}
        message="Someone else saved a different version."
        onReloadAndStartOver={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText(/someone else saved a different version/i)).toBeInTheDocument();
  });

  it("renders the draft as a labelled, keyboard-focusable scroll region", () => {
    render(
      <ConflictPanel
        draft={draft}
        message="conflict"
        onReloadAndStartOver={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    const region = screen.getByRole("region", { name: /draft/i });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(region).toHaveTextContent("AAPL");
    expect(region).toHaveTextContent("BTC");
  });

  it("renders draft rows as non-interactive — no checkbox role or tabindex on rows", () => {
    render(
      <ConflictPanel
        draft={draft}
        message="conflict"
        onReloadAndStartOver={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("exposes exactly two exits: reload-and-start-over, and close", () => {
    const onReloadAndStartOver = vi.fn();
    const onClose = vi.fn();
    render(
      <ConflictPanel
        draft={draft}
        message="conflict"
        onReloadAndStartOver={onReloadAndStartOver}
        onClose={onClose}
      />,
    );

    screen.getByRole("button", { name: /reload/i }).click();
    expect(onReloadAndStartOver).toHaveBeenCalledTimes(1);

    screen.getByRole("button", { name: /discard.*close/i }).click();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
