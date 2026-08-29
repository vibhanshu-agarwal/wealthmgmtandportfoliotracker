import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";
import { FreshnessStatus } from "./FreshnessStatus";

function freshness(overrides: Partial<AssetPriceFreshnessDTO> = {}): AssetPriceFreshnessDTO {
  return {
    state: "FRESH",
    staleHoldings: 0,
    unknownPriceHoldings: 0,
    missingPriceHoldings: 0,
    ...overrides,
  };
}

describe("FreshnessStatus", () => {
  it("renders the compact summary and a Details control", () => {
    render(<FreshnessStatus freshness={freshness({ state: "STALE", staleHoldings: 1 })} />);
    expect(screen.getByText(/1 holding stale/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /details/i })).toBeInTheDocument();
  });

  it("renders nothing when freshness data is not yet available", () => {
    const { container } = render(<FreshnessStatus freshness={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
