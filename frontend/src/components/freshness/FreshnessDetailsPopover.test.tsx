/**
 * B2 Task 1.17 — `FreshnessDetailsPopover`, full interaction/content contract
 * (requirements.md 3a): aria-haspopup="dialog" + aria-expanded + aria-controls, focus
 * moves into the popover on open and returns to the button on close.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";
import { FreshnessDetailsPopover } from "./FreshnessDetailsPopover";

function freshness(overrides: Partial<AssetPriceFreshnessDTO> = {}): AssetPriceFreshnessDTO {
  return {
    state: "FRESH",
    staleHoldings: 0,
    unknownPriceHoldings: 0,
    missingPriceHoldings: 0,
    ...overrides,
  };
}

describe("FreshnessDetailsPopover", () => {
  it("the button carries aria-haspopup=dialog and reflects aria-expanded", async () => {
    render(<FreshnessDetailsPopover freshness={freshness()} />);
    const button = screen.getByRole("button", { name: /details/i });
    expect(button).toHaveAttribute("aria-haspopup", "dialog");
    expect(button).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(button);
    await waitFor(() => expect(button).toHaveAttribute("aria-expanded", "true"));
  });

  it("opens a dialog with an accessible name, and moves focus into it", async () => {
    render(<FreshnessDetailsPopover freshness={freshness()} />);
    fireEvent.click(screen.getByRole("button", { name: /details/i }));

    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
    await waitFor(() =>
      expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement),
    );
  });

  it("shows one all-fresh line when every count is zero", async () => {
    render(<FreshnessDetailsPopover freshness={freshness()} />);
    fireEvent.click(screen.getByRole("button", { name: /details/i }));
    await waitFor(() => expect(screen.getByText(/all prices fresh/i)).toBeInTheDocument());
  });

  it("shows per-state counts, omitting zero rows", async () => {
    render(
      <FreshnessDetailsPopover freshness={freshness({ state: "STALE", staleHoldings: 2 })} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /details/i }));
    await waitFor(() => expect(screen.getByText(/stale: 2/i)).toBeInTheDocument());
    expect(screen.queryByText(/unknown:/i)).not.toBeInTheDocument();
  });

  it("shows the absolute timestamp, not a relative phrase", async () => {
    render(
      <FreshnessDetailsPopover
        freshness={freshness({ oldestKnownAssetPriceObservationTimestamp: "2026-08-14T08:00:12Z" })}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /details/i }));
    await waitFor(() => expect(screen.getByText(/2026/)).toBeInTheDocument());
  });

  it("returns focus to the Details button on close", async () => {
    render(<FreshnessDetailsPopover freshness={freshness()} />);
    const button = screen.getByRole("button", { name: /details/i });
    fireEvent.click(button);

    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });

    await waitFor(() => expect(document.activeElement).toBe(button));
  });
});
