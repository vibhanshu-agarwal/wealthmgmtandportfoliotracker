/**
 * B2 Task 1.4 (`EditHoldingsButton`) + Task 1.5 (fidelity preflight).
 *
 * The preflight runs entirely inside this button's own click handler, before
 * `AssetPickerModal` is ever invoked. If any source holding carries
 * `quantityFidelityUnverified: true`, the button SHALL NOT open the modal, and SHALL
 * carry an accessible, non-color-only notice via `aria-describedby`.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { EditHoldingsButton } from "./EditHoldingsButton";

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

function renderButton(holdings: AssetHoldingDTO[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  server.use(
    http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
    http.get("/api/market/prices", () => HttpResponse.json([])),
    http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
  );
  return render(
    <QueryClientProvider client={client}>
      <EditHoldingsButton holdings={holdings} version={7} userId="user-001" token="test-token" />
    </QueryClientProvider>,
  );
}

describe("EditHoldingsButton — all verified", () => {
  it("opens the modal on click", async () => {
    renderButton([holding()]);
    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
  });
});

describe("EditHoldingsButton — fidelity preflight (Task 1.5)", () => {
  it("does not open the modal when any holding is unverified", () => {
    renderButton([holding({ quantityFidelityUnverified: true })]);
    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("associates an accessible notice with the button via aria-describedby", () => {
    renderButton([holding({ quantityFidelityUnverified: true })]);
    const button = screen.getByRole("button", { name: "Edit Holdings" });
    const describedBy = button.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(/temporarily unavailable/i);
  });

  it("shows the notice up front, not only after a click", () => {
    renderButton([holding({ quantityFidelityUnverified: true })]);
    expect(screen.getByText(/temporarily unavailable/i)).toBeInTheDocument();
  });

  it("refuses when any single holding among several is unverified", () => {
    renderButton([holding({ ticker: "AAPL" }), holding({ ticker: "BTC", quantityFidelityUnverified: true })]);
    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("does not carry aria-describedby when every holding is verified", () => {
    renderButton([holding()]);
    expect(screen.getByRole("button", { name: "Edit Holdings" })).not.toHaveAttribute(
      "aria-describedby",
    );
  });
});

describe("EditHoldingsButton — post-save confirmation (Task 1.13)", () => {
  it("announces success as a role=status live region after a successful save", async () => {
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
      http.get("/api/market/prices", () => HttpResponse.json([])),
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [],
        }),
      ),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <EditHoldingsButton holdings={[]} version={7} userId="user-001" token="test-token" />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/saved/i));
  });
});
