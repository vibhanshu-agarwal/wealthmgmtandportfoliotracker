/**
 * B2 Task 1.7 (BrowseStep) / Task 1.8 (validator wiring) / Task 1.9 (duplicate
 * prevention) / requirements.md 2.4 (reduce-or-remove-only for a retained deprecated
 * position).
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import type { CatalogAsset, DraftHoldings } from "@/types/assetPicker";
import { BrowseStep } from "./BrowseStep";
import { seedDraftFromHoldings } from "./draftState";
import type { AssetHoldingDTO } from "@/types/portfolio";

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

const catalog: CatalogAsset[] = [
  { ticker: "AAPL", name: "Apple Inc.", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
  { ticker: "GOOGL", name: "Alphabet", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
];

function renderBrowseStep(draft: DraftHoldings, onDraftChange = vi.fn()) {
  const initialQuantities = new Map(
    Array.from(draft.values(), (h) => [h.ticker, h.quantity] as const),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  server.use(
    http.get("/api/market/prices", () => HttpResponse.json([])),
  );
  render(
    <QueryClientProvider client={client}>
      <BrowseStep
        catalog={catalog}
        draft={draft}
        onDraftChange={onDraftChange}
        initialQuantities={initialQuantities}
        token="test-token"
      />
    </QueryClientProvider>,
  );
  return onDraftChange;
}

describe("BrowseStep — search", () => {
  it("filters the visible rows as the user types", () => {
    renderBrowseStep(new Map());
    fireEvent.change(screen.getByLabelText(/search assets by ticker or name/i), {
      target: { value: "goog" },
    });
    expect(screen.getByRole("checkbox", { name: "Select GOOGL" })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Select AAPL" })).not.toBeInTheDocument();
  });
});

describe("BrowseStep — selection", () => {
  it("adds an unchecked active asset to the draft on toggle", () => {
    const onDraftChange = renderBrowseStep(new Map());
    fireEvent.click(screen.getByRole("checkbox", { name: "Select AAPL" }));

    const next = onDraftChange.mock.calls[0][0] as DraftHoldings;
    expect(next.has("AAPL")).toBe(true);
    expect(next.get("AAPL")?.source).toBe("added");
  });

  it("removes a checked ticker from the draft on toggle (deselect means delete)", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL" })], catalog);
    const onDraftChange = renderBrowseStep(draft);

    fireEvent.click(screen.getByRole("checkbox", { name: "Select AAPL" }));

    const next = onDraftChange.mock.calls[0][0] as DraftHoldings;
    expect(next.has("AAPL")).toBe(false);
  });

  it("Task 1.9: selecting an already-drafted ticker edits the row instead of adding a second one", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL", quantity: "10" })], catalog);
    const onDraftChange = renderBrowseStep(draft);

    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "10.5" },
    });

    const next = onDraftChange.mock.calls[0][0] as DraftHoldings;
    expect(next.size).toBe(1);
    expect(next.get("AAPL")?.quantity).toBe("10.5");
  });
});

describe("BrowseStep — validation", () => {
  it("shows a validation error for a malformed quantity, associated with the input", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL", quantity: "10" })], catalog);
    renderBrowseStep(draft);

    fireEvent.change(screen.getByRole("textbox", { name: "AAPL quantity" }), {
      target: { value: "abc" },
    });

    const input = screen.getByRole("textbox", { name: "AAPL quantity" });
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(/plain decimal/i);
  });

  it("rejects an increase attempt on a retained deprecated position, client-side", () => {
    const draft = seedDraftFromHoldings(
      [holding({ ticker: "OLDCO", quantity: "5" })],
      [
        {
          ticker: "OLDCO",
          name: "Old Co",
          aliases: [],
          assetClass: "STOCK",
          quoteCurrency: "USD",
          lifecycleStatus: "DEPRECATED",
        },
      ],
    );
    renderBrowseStep(draft);

    fireEvent.change(screen.getByRole("textbox", { name: "OLDCO quantity" }), {
      target: { value: "6" },
    });

    const input = screen.getByRole("textbox", { name: "OLDCO quantity" });
    const describedBy = input.getAttribute("aria-describedby");
    expect(document.getElementById(describedBy!.split(" ")[0])).toHaveTextContent(
      /no longer offered/i,
    );
  });
});

describe("BrowseStep — selected-asset pricing (Task 1.10)", () => {
  it("shows the estimated value for a checked row when a price is available", async () => {
    server.use(
      http.get("/api/market/prices", () =>
        HttpResponse.json([
          { ticker: "AAPL", currentPrice: 100, observedAt: "2026-01-01T00:00:00Z", priceUnavailable: false },
        ]),
      ),
    );

    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL", quantity: "10" })], catalog);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <BrowseStep
          catalog={catalog}
          draft={draft}
          onDraftChange={vi.fn()}
          initialQuantities={new Map([["AAPL", "10"]])}
          token="test-token"
        />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByText("$1,000.00")).toBeInTheDocument());
  });

  it("does not fetch a price for an unchecked, undrafted asset", () => {
    let requestedTickers: string | null = null;
    server.use(
      http.get("/api/market/prices", ({ request }) => {
        requestedTickers = new URL(request.url).searchParams.get("tickers");
        return HttpResponse.json([]);
      }),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <BrowseStep
          catalog={catalog}
          draft={new Map()}
          onDraftChange={vi.fn()}
          initialQuantities={new Map()}
          token="test-token"
        />
      </QueryClientProvider>,
    );

    expect(requestedTickers).toBeNull();
  });
});
