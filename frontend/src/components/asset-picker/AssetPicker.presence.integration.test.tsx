/**
 * B2 Task 9.4 — presence lifecycle at the component level, not the hook level.
 *
 * `usePresence.test.tsx` drives `openKey` by hand, so it can only prove the hook
 * refetches when that key changes; it cannot prove how many times `AssetPicker`
 * actually changes the key for one modal opening. Requirement 6.3 ("query presence
 * **once**, on open") is a property of the composed component, so it is asserted
 * here against the real `useCatalog`/`usePresence` wiring through MSW.
 *
 * The fail-open cases below are deliberately *injected* failures (Requirement 6.5 /
 * GC.5). They are not, and must not be read as, evidence about the real endpoint —
 * that is the assembled-stack proof's job.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { createRef, type ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { AssetPicker } from "./AssetPicker";

const BANNER_TEXT = "Another demo session is active — your changes may not save.";

const CATALOG = {
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
    {
      ticker: "MSFT",
      name: "Microsoft Corp.",
      aliases: [],
      assetClass: "STOCK",
      quoteCurrency: "USD",
      lifecycleStatus: "ACTIVE",
    },
  ],
};

function holding(): AssetHoldingDTO {
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
  };
}

type PresenceMode = "ok" | "http-500" | "network-error" | "invalid-json";

/**
 * Counts real presence GETs. `answer` and `mode` stay mutable so a test can change
 * what the endpoint says *between* openings without depending on call ordering.
 */
function presenceRoute(initial: { answer?: boolean; mode?: PresenceMode } = {}) {
  const state = {
    count: 0,
    answer: initial.answer ?? false,
    mode: initial.mode ?? ("ok" as PresenceMode),
  };
  server.use(
    http.get("/api/presence/demo", () => {
      state.count += 1;
      if (state.mode === "http-500") return new HttpResponse(null, { status: 500 });
      if (state.mode === "network-error") return HttpResponse.error();
      if (state.mode === "invalid-json") {
        return new HttpResponse("<!doctype html>not json", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return HttpResponse.json({ anotherSessionActive: state.answer });
    }),
  );
  return state;
}

/** `catalogDelayMs > 0` reproduces a cold catalog; 0 keeps the default fast path. */
function catalogRoute(catalogDelayMs = 0) {
  server.use(
    http.get("/api/assets", async () => {
      if (catalogDelayMs > 0) await delay(catalogDelayMs);
      return HttpResponse.json(CATALOG);
    }),
    http.get("/api/market/prices", () => HttpResponse.json([])),
  );
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function warmClient() {
  const client = newClient();
  client.setQueryData(["asset-picker", "catalog"], CATALOG);
  return client;
}

function pickerTree(client: QueryClient, open: boolean): ReactElement {
  const triggerRef = createRef<HTMLButtonElement>();
  return (
    <QueryClientProvider client={client}>
      <AssetPicker
        open={open}
        onClose={vi.fn()}
        initialHoldings={[holding()]}
        initialVersion={7}
        userId="user-001"
        token="test-token"
        triggerRef={triggerRef}
      />
    </QueryClientProvider>
  );
}

/** The draft is seeded only once the catalog resolves — the end of one opening. */
async function waitForSeededDraft() {
  await waitFor(() =>
    expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toHaveAttribute(
      "aria-checked",
      "true",
    ),
  );
}

/** Lets any late, generation-triggered refetch land before a count is asserted. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 60));
}

describe("AssetPicker presence — exactly one GET per opening (Requirement 6.3)", () => {
  it("dispatches exactly one presence GET for an opening with a cold catalog", async () => {
    catalogRoute(40);
    const presence = presenceRoute();

    render(pickerTree(newClient(), true));
    await waitForSeededDraft();
    await settle();

    expect(presence.count).toBe(1);
  });

  it("dispatches exactly one presence GET for an opening with a warm catalog", async () => {
    catalogRoute();
    const presence = presenceRoute();

    render(pickerTree(warmClient(), true));
    await waitForSeededDraft();
    await settle();

    expect(presence.count).toBe(1);
  });

  it("dispatches exactly one further presence GET when the modal is closed and reopened", async () => {
    catalogRoute();
    const presence = presenceRoute();
    const client = warmClient();

    const { rerender } = render(pickerTree(client, true));
    await waitForSeededDraft();
    await settle();
    const afterFirstOpen = presence.count;

    rerender(pickerTree(client, false));
    rerender(pickerTree(client, true));
    await waitForSeededDraft();
    await settle();

    expect(presence.count - afterFirstOpen).toBe(1);
  });

  it("never queries presence while the modal is closed", async () => {
    catalogRoute();
    const presence = presenceRoute();

    render(pickerTree(warmClient(), false));
    await settle();

    expect(presence.count).toBe(0);
  });

  it("does not re-query on draft edits, rerenders, or window focus within one opening", async () => {
    catalogRoute();
    const presence = presenceRoute();
    const client = warmClient();

    const { rerender } = render(pickerTree(client, true));
    await waitForSeededDraft();
    await settle();
    const afterOpen = presence.count;

    // A draft edit: uncheck the seeded holding, then select another asset.
    fireEvent.click(screen.getByRole("checkbox", { name: "Select AAPL" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select MSFT" }));
    // A plain rerender of the same opening.
    rerender(pickerTree(client, true));
    // Window focus — React Query's refetchOnWindowFocus must not reach presence.
    fireEvent.focus(window);
    await settle();

    expect(presence.count).toBe(afterOpen);
  });
});

describe("AssetPicker presence — banner reflects the current opening only", () => {
  it("shows one persistent banner on true and none on false, across separate openings", async () => {
    catalogRoute();
    const presence = presenceRoute({ answer: false });
    const client = warmClient();

    // Opening 1 — false: no banner.
    const { rerender } = render(pickerTree(client, true));
    await waitForSeededDraft();
    await settle();
    expect(screen.queryByText(BANNER_TEXT)).not.toBeInTheDocument();

    // Opening 2 — true: exactly one banner.
    presence.answer = true;
    rerender(pickerTree(client, false));
    rerender(pickerTree(client, true));
    await waitForSeededDraft();
    await waitFor(() => expect(screen.getAllByText(BANNER_TEXT)).toHaveLength(1));

    // Opening 3 — false again: the previous opening's cached true must not persist.
    presence.answer = false;
    rerender(pickerTree(client, false));
    rerender(pickerTree(client, true));
    await waitForSeededDraft();
    await waitFor(() => expect(screen.queryByText(BANNER_TEXT)).not.toBeInTheDocument());
  });

  it("keeps a true banner for the whole opening without polling", async () => {
    catalogRoute();
    const presence = presenceRoute({ answer: true });
    const client = warmClient();

    render(pickerTree(client, true));
    await waitForSeededDraft();
    await waitFor(() => expect(screen.getAllByText(BANNER_TEXT)).toHaveLength(1));
    const afterResolved = presence.count;

    await new Promise((resolve) => setTimeout(resolve, 250));

    expect(screen.getAllByText(BANNER_TEXT)).toHaveLength(1);
    expect(presence.count).toBe(afterResolved);
  });
});

describe("AssetPicker presence — fail-open on injected failures (Requirement 6.5 / GC.5)", () => {
  it.each([
    ["an HTTP non-OK response", "http-500" as PresenceMode],
    ["a rejected network fetch", "network-error" as PresenceMode],
    ["an invalid JSON body", "invalid-json" as PresenceMode],
  ])("renders no banner and keeps the draft editable on %s", async (_label, mode) => {
    catalogRoute();
    presenceRoute({ mode });

    render(pickerTree(warmClient(), true));
    await waitForSeededDraft();
    await settle();

    expect(screen.queryByText(BANNER_TEXT)).not.toBeInTheDocument();
    expect(screen.queryByText(/presence/i)).not.toBeInTheDocument();

    // The draft still responds to edits — presence never gates editing.
    fireEvent.click(screen.getByRole("checkbox", { name: "Select MSFT" }));
    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select MSFT" })).toHaveAttribute(
        "aria-checked",
        "true",
      ),
    );
  });
});

describe("AssetPicker presence — a true banner does not gate draft editing", () => {
  it("lets a seeded holding be unchecked while the advisory banner is showing", async () => {
    catalogRoute();
    presenceRoute({ answer: true });

    render(pickerTree(warmClient(), true));
    await waitForSeededDraft();
    await waitFor(() => expect(screen.getAllByText(BANNER_TEXT)).toHaveLength(1));

    // AAPL is seeded from `initialHoldings`, so this unchecks a *held* row —
    // the case the real-stack spec drives, where drafted rows sort first and a
    // positional locator would silently follow a different row instead.
    const seeded = screen.getByRole("checkbox", { name: "Select AAPL" });
    expect(seeded).toHaveAttribute("aria-checked", "true");
    fireEvent.click(seeded);

    await waitFor(() =>
      expect(screen.getByRole("checkbox", { name: "Select AAPL" })).toHaveAttribute(
        "aria-checked",
        "false",
      ),
    );
    // The banner is still there: editing neither cleared nor re-queried it.
    expect(screen.getAllByText(BANNER_TEXT)).toHaveLength(1);
  });
});
