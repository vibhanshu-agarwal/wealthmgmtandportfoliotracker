/**
 * B2 Task 1.7 — `DraftRow`, and the `RetainedDeprecatedRow` variant.
 *
 * Control semantics per requirements.md 1.8: native/role checkbox with a live
 * aria-checked + aria-label naming the asset; quantity input with its own aria-label;
 * a retained-deprecated row keeps its checkbox fully operable while its quantity input
 * is reduce-or-remove-only, with a client-side rejection on an increase attempt.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DraftRow } from "./DraftRow";

describe("DraftRow — active asset", () => {
  it("exposes a checkbox labelled by the asset, checked when selected", () => {
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
      />,
    );

    const checkbox = screen.getByRole("checkbox", { name: "Select AAPL" });
    expect(checkbox).toHaveAttribute("aria-checked", "true");
  });

  it("labels the quantity input by the asset", () => {
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
      />,
    );

    expect(screen.getByRole("textbox", { name: "AAPL quantity" })).toHaveValue("10");
  });

  it("calls onToggle when the checkbox is activated", () => {
    const onToggle = vi.fn();
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={onToggle}
        onQuantityChange={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("checkbox", { name: "Select AAPL" }));
    expect(onToggle).toHaveBeenCalledWith("AAPL");
  });

  it("calls onQuantityChange with the raw typed value, not a parsed number", () => {
    const onQuantityChange = vi.fn();
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={onQuantityChange}
      />,
    );

    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "10.5" },
    });
    expect(onQuantityChange).toHaveBeenCalledWith("AAPL", "10.5");
  });

  it("associates a validation error via aria-describedby, not color alone", () => {
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="abc"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
        errorMessage="Enter a quantity as a plain decimal number, for example 12.5."
      />,
    );

    const input = screen.getByRole("textbox", { name: "AAPL quantity" });
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(/plain decimal number/);
  });
});

describe("DraftRow — RetainedDeprecatedRow variant", () => {
  it("keeps the checkbox fully operable — no aria-disabled", () => {
    render(
      <DraftRow
        ticker="TATAMOTORS.NS"
        name="Tata Motors"
        quantity="5"
        checked
        lifecycleStatus="DEPRECATED"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
      />,
    );

    const checkbox = screen.getByRole("checkbox", { name: "Select TATAMOTORS.NS" });
    expect(checkbox).not.toHaveAttribute("aria-disabled");
    expect(checkbox).toHaveAttribute("aria-checked", "true");
  });

  it("shows a reduce-or-remove-only explanation linked via aria-describedby", () => {
    render(
      <DraftRow
        ticker="TATAMOTORS.NS"
        name="Tata Motors"
        quantity="5"
        checked
        lifecycleStatus="DEPRECATED"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
      />,
    );

    const input = screen.getByRole("textbox", { name: "TATAMOTORS.NS quantity" });
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(/no longer offered/i);
  });

  it("carries max as a supplementary hint only, not primary enforcement", () => {
    render(
      <DraftRow
        ticker="TATAMOTORS.NS"
        name="Tata Motors"
        quantity="5"
        checked
        lifecycleStatus="DEPRECATED"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
      />,
    );

    const input = screen.getByRole("textbox", { name: "TATAMOTORS.NS quantity" });
    expect(input).toHaveAttribute("max", "5");
    expect(input).toHaveAttribute("inputmode", "decimal");
  });

  it("still calls onQuantityChange for a client-rejected increase — enforcement is a validation check, not input blocking", () => {
    const onQuantityChange = vi.fn();
    render(
      <DraftRow
        ticker="TATAMOTORS.NS"
        name="Tata Motors"
        quantity="5"
        checked
        lifecycleStatus="DEPRECATED"
        onToggle={vi.fn()}
        onQuantityChange={onQuantityChange}
      />,
    );

    fireEvent.change(screen.getByRole("textbox", { name: "TATAMOTORS.NS quantity" }), {
      target: { value: "6" },
    });
    expect(onQuantityChange).toHaveBeenCalledWith("TATAMOTORS.NS", "6");
  });
});

describe("DraftRow — estimated value (Task 1.10)", () => {
  it("shows the estimated value when a price is available", () => {
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
        estimatedValue={1000}
      />,
    );
    expect(screen.getByText("$1,000.00")).toBeInTheDocument();
  });

  it("shows nothing for estimated value when the price is unavailable", () => {
    render(
      <DraftRow
        ticker="AAPL"
        name="Apple Inc."
        quantity="10"
        checked
        lifecycleStatus="ACTIVE"
        onToggle={vi.fn()}
        onQuantityChange={vi.fn()}
        estimatedValue={null}
      />,
    );
    expect(screen.queryByText(/^\$/)).not.toBeInTheDocument();
  });
});
