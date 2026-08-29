import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AssetSearchBar } from "./AssetSearchBar";

describe("AssetSearchBar", () => {
  it("has an accessible label for searching by ticker or name", () => {
    render(<AssetSearchBar value="" onChange={vi.fn()} />);
    expect(screen.getByLabelText(/search assets by ticker or name/i)).toBeInTheDocument();
  });

  it("calls onChange with the raw typed text", () => {
    const onChange = vi.fn();
    render(<AssetSearchBar value="" onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(/search assets by ticker or name/i), {
      target: { value: "goog" },
    });
    expect(onChange).toHaveBeenCalledWith("goog");
  });
});
