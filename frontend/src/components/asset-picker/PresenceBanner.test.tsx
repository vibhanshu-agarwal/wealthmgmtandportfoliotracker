import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PresenceBanner } from "./PresenceBanner";

describe("PresenceBanner", () => {
  it("shows the persistent advisory when another session is active", () => {
    render(<PresenceBanner anotherSessionActive />);
    expect(
      screen.getByText(/another demo session is active/i),
    ).toBeInTheDocument();
  });

  it("renders nothing on absence", () => {
    const { container } = render(<PresenceBanner anotherSessionActive={false} />);
    expect(container).toBeEmptyDOMElement();
  });
});
