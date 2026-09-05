/**
 * B2 — the orchestrating `AssetPicker`: owns draft state (Task 1.6), seeds it per GC.1,
 * and composes `AssetPickerModal` + `BrowseStep`. Review/Save/Conflict/Presence land in
 * Checkpoint 3.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { AssetPicker } from "./AssetPicker";

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

function renderPicker(props: Partial<React.ComponentProps<typeof AssetPicker>> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const triggerRef = createRef<HTMLButtonElement>();
  server.use(
    http.get("/api/market/prices", () => HttpResponse.json([])),
    http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
  );
  const utils = render(
    <QueryClientProvider client={client}>
      <AssetPicker
        open
        onClose={vi.fn()}
        initialHoldings={[holding()]}
        initialVersion={7}
        userId="user-001"
        token="test-token"
        triggerRef={triggerRef}
        {...props}
      />
    </QueryClientProvider>,
  );
  return utils;
}

describe("AssetPicker — GC.1 seeding", () => {
  it("seeds the draft with every held ticker, checked, once the catalog loads", async () => {
    server.use(
      http.get("/api/assets", () =>
        HttpResponse.json({
          catalogVersion: "v1",
          assets: [
            { ticker: "AAPL", name: "Apple Inc.", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
          ],
        }),
      ),
    );

    renderPicker();

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toHaveAttribute(
        "aria-checked",
        "true",
      ),
    );
  });

  it("preserves the seeded quantity verbatim, including trailing zeros", async () => {
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
    );

    renderPicker({ initialHoldings: [holding({ quantity: "10.00000000" })] });

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "AAPL quantity" })).toHaveValue("10.00000000"),
    );
  });
});

describe("AssetPicker — modal shell", () => {
  it("renders the dialog labelled Edit Holdings", async () => {
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
    );
    renderPicker();
    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
  });
});

describe("AssetPicker — review, save, conflict (Checkpoint 3)", () => {
  function stubCatalogAndPrices() {
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
    );
  }

  it("moves from Browse to Review, then submits the full draft as one PUT with expectedVersion", async () => {
    stubCatalogAndPrices();
    let receivedBody: unknown = null;
    server.use(
      http.put("/api/portfolio/holdings", async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
        });
      }),
    );

    const onClose = vi.fn();
    renderPicker({ initialVersion: 7, onClose, userId: "user-001" });

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(receivedBody).toEqual({
      expectedVersion: 7,
      holdings: [{ ticker: "AAPL", quantity: "10" }],
    }));
  });

  it("on 200: closes the modal (GC.3/1.13 success transition)", async () => {
    stubCatalogAndPrices();
    server.use(
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

    const onClose = vi.fn();
    renderPicker({ initialHoldings: [], onClose, userId: "user-001" });

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /review changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("on 409: enters the frozen ConflictPanel state — draft stays visible, no automatic resubmit", async () => {
    stubCatalogAndPrices();
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/holdings", () => {
        putCount += 1;
        return HttpResponse.json(
          { error: "portfolio_version_conflict", message: "Someone else saved a different version.", currentVersion: 9 },
          { status: 409 },
        );
      }),
    );

    renderPicker({ userId: "user-001" });

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() =>
      expect(screen.getByText(/someone else saved a different version/i)).toBeInTheDocument(),
    );
    // The draft is still visible in the conflict panel's read-only region.
    expect(screen.getByRole("region", { name: /draft/i })).toHaveTextContent("AAPL");
    expect(putCount).toBe(1);

    // Waiting past the conflict does not trigger a second, automatic PUT.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(putCount).toBe(1);
  });

  it("GC.6: zero GET /api/portfolio calls occur between the modal opening and the save PUT firing", async () => {
    stubCatalogAndPrices();
    let getPortfolioCalls = 0;
    server.use(
      http.get("/api/portfolio", () => {
        getPortfolioCalls += 1;
        return HttpResponse.json([]);
      }),
      http.put("/api/portfolio/holdings", () =>
        HttpResponse.json({ id: "p1", userId: "user-001", createdAt: "2026-01-01T00:00:00Z", version: 8, holdings: [] }),
      ),
    );

    renderPicker({ userId: "user-001" });

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument(),
    );

    // Nothing in this whole flow — catalog load, seeding, browse, review — should have
    // triggered a GET /api/portfolio; the version came from the props passed at open.
    expect(getPortfolioCalls).toBe(0);

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    await waitFor(() => expect(getPortfolioCalls).toBe(0));
  });
});

describe("AssetPicker — frozen open-time baseline (review-fix)", () => {
  function stubCatalogAndPrices() {
    server.use(
      http.get("/api/assets", () => HttpResponse.json({ catalogVersion: "v1", assets: [] })),
      http.get("/api/market/prices", () => HttpResponse.json([])),
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: false })),
    );
  }

  it("a live prop refresh while the modal is open does not change the submitted expectedVersion or the review baseline", async () => {
    stubCatalogAndPrices();
    let receivedBody: unknown = null;
    server.use(
      http.put("/api/portfolio/holdings", async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [],
        });
      }),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const triggerRef = createRef<HTMLButtonElement>();

    // Simulate usePortfolio's 60s refetchInterval delivering fresher props to a
    // still-open picker — the parent re-renders AssetPicker with a NEW version and a
    // NEW holdings array reference, exactly as EditHoldingsButton would when
    // PortfolioPageContent's own usePortfolio() resolves a background refetch.
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <AssetPicker
          open
          onClose={vi.fn()}
          initialHoldings={[holding({ ticker: "AAPL", quantity: "10" })]}
          initialVersion={7}
          userId="user-001"
          token="test-token"
          triggerRef={triggerRef}
        />
      </QueryClientProvider>,
    );

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toBeInTheDocument(),
    );

    // A background refetch landed: version bumped to 9, and AAPL's own quantity
    // changed server-side too (someone else already saved a change).
    rerender(
      <QueryClientProvider client={client}>
        <AssetPicker
          open
          onClose={vi.fn()}
          initialHoldings={[holding({ ticker: "AAPL", quantity: "999" })]}
          initialVersion={9}
          userId="user-001"
          token="test-token"
          triggerRef={triggerRef}
        />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /review changes/i }));

    // The review diff must still be computed against the ORIGINAL open-time
    // baseline (quantity "10"), not the value that arrived after a background
    // refetch — otherwise a real edit could be silently masked as "unchanged".
    await waitFor(() => expect(screen.getByText(/1 in draft/i)).toBeInTheDocument());
    expect(screen.queryByText(/999/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(receivedBody).not.toBeNull());
    // GC.6: expectedVersion is the value captured when the modal opened (7), never
    // a later value that arrived from a background refetch while it was open.
    expect(receivedBody).toEqual({
      expectedVersion: 7,
      holdings: [{ ticker: "AAPL", quantity: "10" }],
    });
  });
});

describe("AssetPicker — catalog failure (Task 9.1)", () => {
  it("shows a visible error and blocks Review when the initial catalog fetch fails, instead of an apparently-empty Browse", async () => {
    server.use(http.get("/api/assets", () => new HttpResponse(null, { status: 500 })));

    renderPicker();

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/couldn.?t load the asset catalog/i),
    );
    // No Browse/Review surface reachable from a catalog that never loaded.
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /review changes/i })).not.toBeInTheDocument();
  });

  it("recovers to Browse once Retry succeeds", async () => {
    let attempt = 0;
    server.use(
      http.get("/api/assets", () => {
        attempt += 1;
        if (attempt === 1) return new HttpResponse(null, { status: 500 });
        return HttpResponse.json({
          catalogVersion: "v1",
          assets: [
            {
              ticker: "AAPL",
              name: "Apple Inc.",
              aliases: [],
              assetClass: "STOCK",
              quoteCurrency: "USD",
              lifecycleStatus: "ACTIVE",
            },
          ],
        });
      }),
    );

    renderPicker();

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toHaveAttribute(
        "aria-checked",
        "true",
      ),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("AssetPicker — Review changes is blocked while the draft is invalid (review-fix)", () => {
  function stubCatalogAndPrices() {
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
  }

  it("disables Review changes while a drafted quantity is malformed", async () => {
    stubCatalogAndPrices();
    renderPicker({ initialHoldings: [holding({ ticker: "AAPL", quantity: "10" })] });

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "AAPL quantity" })).toBeInTheDocument(),
    );
    expect(screen.getByRole("button", { name: /review changes/i })).toBeEnabled();

    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "abc" },
    });

    await waitFor(() => expect(screen.getByRole("button", { name: /review changes/i })).toBeDisabled());
  });

  it("re-enables Review changes once the value is corrected", async () => {
    stubCatalogAndPrices();
    renderPicker({ initialHoldings: [holding({ ticker: "AAPL", quantity: "10" })] });

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "AAPL quantity" })).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "0" },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: /review changes/i })).toBeDisabled());

    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "5" },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: /review changes/i })).toBeEnabled());
  });
});
