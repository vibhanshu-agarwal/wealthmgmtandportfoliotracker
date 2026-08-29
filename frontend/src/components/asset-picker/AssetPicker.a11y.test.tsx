/**
 * B2 Task 1.19 / GC.12 — the automated accessibility check, run against the built
 * modal rather than verified by visual review of the mockup alone.
 *
 * `jest-axe` (axe-core under jsdom) is the explicit example GC.12 names. Runs against
 * every reachable state of the completed picker: Browse, Review, and the frozen
 * Conflict state — GC.12 governs "the finished picker, not only the shell".
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createRef } from "react";
import { axe, toHaveNoViolations } from "jest-axe";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { AssetPicker } from "./AssetPicker";

expect.extend(toHaveNoViolations);

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

function renderOpenPicker() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const triggerRef = createRef<HTMLButtonElement>();
  server.use(
    http.get("/api/assets", () =>
      HttpResponse.json({
        catalogVersion: "v1",
        assets: [
          { ticker: "AAPL", name: "Apple Inc.", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
        ],
      }),
    ),
    http.get("/api/market/prices", () => HttpResponse.json([])),
    http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
  );
  render(
    <QueryClientProvider client={client}>
      <AssetPicker
        open
        onClose={() => {}}
        initialHoldings={[holding()]}
        initialVersion={7}
        userId="user-001"
        token="test-token"
        triggerRef={triggerRef}
      />
    </QueryClientProvider>,
  );
}

describe("GC.12 — automated accessibility audit of the built modal", () => {
  it("Browse step has no detectable violations", async () => {
    renderOpenPicker();
    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toBeInTheDocument(),
    );

    const results = await axe(screen.getByRole("dialog"));
    expect(results).toHaveNoViolations();
  });

  it("Review step has no detectable violations", async () => {
    renderOpenPicker();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );

    const results = await axe(screen.getByRole("dialog"));
    expect(results).toHaveNoViolations();
  });

  it("the frozen Conflict state has no detectable violations", async () => {
    server.use(
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json(
          { error: "portfolio_version_conflict", message: "Conflict.", currentVersion: 9 },
          { status: 409 },
        ),
      ),
    );
    renderOpenPicker();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    await waitFor(() => expect(screen.getByRole("region", { name: /draft/i })).toBeInTheDocument());

    const results = await axe(screen.getByRole("dialog"));
    expect(results).toHaveNoViolations();
  });
});
