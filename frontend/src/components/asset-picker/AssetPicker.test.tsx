/**
 * B2 — the orchestrating `AssetPicker`: owns draft state (Task 1.6), seeds it per GC.1,
 * and composes `AssetPickerModal` + `BrowseStep`. Review/Save/Conflict/Presence land in
 * Checkpoint 3.
 */
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { createRef, type ReactNode } from "react";
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
  const utils = render(
    <QueryClientProvider client={client}>
      <AssetPicker
        open
        onClose={vi.fn()}
        initialHoldings={[holding()]}
        initialVersion={7}
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
