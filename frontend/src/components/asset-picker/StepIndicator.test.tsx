import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StepIndicator } from "./StepIndicator";

describe("StepIndicator", () => {
  it("marks the current step with aria-current=step", () => {
    render(<StepIndicator current="review" />);
    const review = screen.getByText("Review");
    expect(review.closest('[aria-current="step"]')).toBeInTheDocument();
    expect(screen.getByText("Browse").closest('[aria-current="step"]')).toBeNull();
  });
});
