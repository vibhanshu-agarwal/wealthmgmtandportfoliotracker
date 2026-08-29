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
import { usePortfolio, usePortfolioSummary } from "@/lib/hooks/usePortfolio";
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

describe("post-save close sequencing (review-fix)", () => {
  it("does not close the modal or announce success until the cache reconciliation has been given a chance to complete", async () => {
    // An object wrapper, not a bare `let`, because TypeScript narrows a `let`
    // reassigned only inside a nested closure to `never` at later read sites.
    const held: { release: (() => void) | null } = { release: null };
    let summaryCallCount = 0;

    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
      http.get("/api/market/prices", () => HttpResponse.json([])),
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
      http.get("/api/portfolio/summary", async () => {
        summaryCallCount += 1;
        if (summaryCallCount > 1) {
          // The post-save reconciliation refetch — held open until the test
          // explicitly releases it, so we can observe what the UI does while it's
          // still in flight.
          await new Promise<void>((resolve) => {
            held.release = resolve;
          });
        }
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

    await waitFor(() => expect(summaryCallCount).toBeGreaterThanOrEqual(1));

    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    // The PUT has resolved and the reconciliation refetch has started (call #2 is
    // in flight, held open) — but hasn't finished. The modal must still be open and
    // no "saved" announcement shown yet.
    await waitFor(() => expect(summaryCallCount).toBe(2));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    // Release the held-open refetch — only now should the modal close and announce.
    held.release?.();

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("status")).toHaveTextContent(/saved/i);
  });
});

describe("save success replaces visible state from the response body (requirements.md 4.2)", () => {
  it("updates the portfolio query cache directly from the PUT response, never from a subsequent GET", async () => {
    let portfolioGetCount = 0;

    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
      http.get("/api/market/prices", ({ request }) => {
        const tickers =
          new URL(request.url).searchParams.get("tickers")?.split(",").filter(Boolean) ?? [];
        return HttpResponse.json(
          tickers.map((ticker) => ({
            ticker,
            currentPrice: 50,
            observedAt: "2026-08-01T00:00:00Z",
            priceUnavailable: false,
          })),
        );
      }),
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
      http.get("/api/portfolio", () => {
        portfolioGetCount += 1;
        // The pre-save GET this test's Harness would use to populate usePortfolio,
        // deliberately distinct from what the PUT will later return.
        return HttpResponse.json([
          {
            id: "p1",
            userId: "user-001",
            createdAt: "2026-01-01T00:00:00Z",
            version: 7,
            holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
          },
        ]);
      }),
      // The PUT response is the SOLE source of truth this test checks against — a
      // different ticker/quantity/version than either the pre-save GET or the draft.
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 42,
          holdings: [{ id: "h9", assetTicker: "GOOGL", quantity: "3" }],
        }),
      ),
    );

    function PortfolioHarness() {
      usePortfolioSummary();
      const { data } = usePortfolio();
      return (
        <>
          <EditHoldingsButton holdings={data?.holdings ?? []} version={data?.version ?? 0} userId="user-001" token="test-token" />
          <div data-testid="visible-holdings">
            {(data?.holdings ?? []).map((h) => `${h.ticker}:${h.quantity}`).join(",")}
          </div>
          <div data-testid="visible-version">{data?.version}</div>
        </>
      );
    }

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <PortfolioHarness />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("visible-holdings")).toHaveTextContent("AAPL:10"));
    const getCallsBeforeSave = portfolioGetCount;

    fireEvent.click(screen.getByRole("button", { name: "Edit Holdings" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    // The visible state must reflect the PUT response's own GOOGL/3/42 — not the
    // stale AAPL/10/7 the draft was built from — and it must do so without any
    // additional GET /api/portfolio having fired.
    await waitFor(() =>
      expect(screen.getByTestId("visible-holdings")).toHaveTextContent("GOOGL:3"),
    );
    expect(screen.getByTestId("visible-version")).toHaveTextContent("42");
    expect(portfolioGetCount).toBe(getCallsBeforeSave);
  });
});
