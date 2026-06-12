import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DemoDataBadge } from "./DemoDataBadge";

describe("DemoDataBadge", () => {
  it("renders badge with Demo label and FlaskConical icon", () => {
    render(<DemoDataBadge />);

    const badge = screen.getByTestId("demo-data-badge");
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveTextContent("Demo");
    expect(badge.querySelector("svg")).toBeInTheDocument();
  });

  it("shows tooltip on hover with seeded-data explanation", async () => {
    render(<DemoDataBadge />);

    const badge = screen.getByTestId("demo-data-badge");
    fireEvent.pointerEnter(badge);
    fireEvent.focus(badge);

    await waitFor(() => {
      expect(screen.getByRole("tooltip")).toHaveTextContent(
        /Showing seeded demo data\. 24h % reflects a deterministic delta/i,
      );
    });
  });
});
