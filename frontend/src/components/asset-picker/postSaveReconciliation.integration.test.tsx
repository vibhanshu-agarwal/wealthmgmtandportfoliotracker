/**
 * B2 Task 1.18 — "the Portfolio page re-reads assetPriceFreshness after a successful
 * save rather than inferring a fresh state the write path cannot produce."
 *
 * Proves the actual mechanism end to end: with `usePortfolioSummary` actively mounted
 * (as it is via `FreshnessStatus` on the real Portfolio page), a successful composition
 * save triggers a real refetch of `GET /api/portfolio/summary` — not just a no-op
 * invalidate against an inactive query.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import { usePortfolioSummary } from "@/lib/hooks/usePortfolio";
import { EditHoldingsButton } from "./EditHoldingsButton";

vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => ({
    userId: "user-001",
    token: "test-token",
    status: "authenticated",
    error: null,
  }),
}));

function Harness() {
  // Mirrors PortfolioPageContent: an active usePortfolioSummary observer alongside
  // EditHoldingsButton, so cache invalidation has something real to refetch.
  usePortfolioSummary();
  return (
    <EditHoldingsButton
      holdings={[]}
      version={7}
      userId="user-001"
      token="test-token"
    />
  );
}

describe("post-save freshness reconciliation (Task 1.18)", () => {
  it("triggers a real GET /api/portfolio/summary refetch after a successful save", async () => {
    let summaryCalls = 0;
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
      http.get("/api/market/prices", () => HttpResponse.json([])),
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
      http.get("/api/portfolio/summary", () => {
        summaryCalls += 1;
        return HttpResponse.json({
          userId: "user-001",
          portfolioCount: 1,
          totalHoldings: 0,
          totalValue: 0,
          assetPriceFreshness: {
            state: "FRESH",
            staleHoldings: 0,
            unknownPriceHoldings: 0,
            missingPriceHoldings: 0,
          },
        });
      }),
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
        <Harness />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(summaryCalls).toBeGreaterThanOrEqual(1));
    const callsBeforeSave = summaryCalls;

    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(summaryCalls).toBeGreaterThan(callsBeforeSave));
  });
});
