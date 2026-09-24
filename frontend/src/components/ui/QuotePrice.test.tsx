import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QuotePrice } from "./QuotePrice";

describe("QuotePrice", () => {
  it("renders an INR price with the rupee sign and no unavailable title", () => {
    render(<QuotePrice value={22470} currency="INR" />);
    const el = screen.getByText("₹22,470.00");
    expect(el).not.toHaveAttribute("title");
  });

  it("renders $ only when the currency is explicitly USD", () => {
    render(<QuotePrice value={337.02} currency="USD" />);
    expect(screen.getByText("$337.02")).not.toHaveAttribute("title");
  });

  it.each([[null], [undefined], ["XYZ"], [""], ["inr"]])(
    "marks the element when the currency is %p, and shows no symbol",
    (currency) => {
      render(<QuotePrice value={22470} currency={currency as string | null | undefined} />);
      const el = screen.getByText("22,470.00");
      expect(el).toHaveAttribute("title", "Currency unavailable");
      expect(el.textContent).not.toMatch(/[$₹¥€£]/);
    },
  );

  it("renders a signed per-unit change in the quote currency", () => {
    render(<QuotePrice value={-17.3} currency="INR" signed />);
    expect(screen.getByText("-₹17.30")).toBeInTheDocument();
  });
});
