/**
 * B2 Tasks 6.1/6.2 — `ManualResetControl`, the hidden manual demo-reset control.
 *
 * Placement here is the temporary, page-level host outside any open picker
 * (requirements.md 7.6 is still OPEN) — so a `409` surfaces as a draft-free notice,
 * not `ConflictPanel`. GC.6: the request carries the version captured at click time,
 * never re-read inside the call and never affected by a later prop update.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import { portfolioKeys, usePortfolio } from "@/lib/hooks/usePortfolio";
import { ManualResetControl } from "./ManualResetControl";

const USER_ID = "user-001";
const TOKEN = "test-token";

vi.mock("@/lib/hooks/useAuthenticatedUserId", () => ({
  useAuthenticatedUserId: () => ({
    userId: USER_ID,
    token: TOKEN,
    status: "authenticated",
    error: null,
  }),
}));

function stubMarketPrices() {
  server.use(
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
  );
}

function renderControl(
  version: number,
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  versionObserved = true,
) {
  render(
    <QueryClientProvider client={client}>
      <ManualResetControl userId={USER_ID} token={TOKEN} version={version} versionObserved={versionObserved} />
    </QueryClientProvider>,
  );
  return client;
}

describe("ManualResetControl — idle and request shape", () => {
  it("renders a single idle Reset control", () => {
    renderControl(0);
    expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled();
  });

  it("sends exactly one PUT carrying the currently observed expectedVersion, including zero", async () => {
    let receivedBody: unknown = null;
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/demo-reset", async ({ request }) => {
        putCount += 1;
        receivedBody = await request.json();
        return HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    renderControl(0);
    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    await waitFor(() => expect(putCount).toBe(1));
    expect(receivedBody).toEqual({ expectedVersion: 0 });
  });

  it("prevents a duplicate submission from a rapid second click while pending", async () => {
    let putCount = 0;
    const held: { release: (() => void) | null } = { release: null };
    server.use(
      http.put("/api/portfolio/demo-reset", async () => {
        putCount += 1;
        await new Promise<void>((resolve) => {
          held.release = resolve;
        });
        return HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    renderControl(4);
    const button = screen.getByRole("button", { name: /reset demo portfolio/i });
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.click(button);

    await waitFor(() => expect(button).toBeDisabled());
    expect(putCount).toBe(1);

    held.release?.();
    await waitFor(() => expect(button).not.toBeDisabled());
  });

  it("ignores a version prop change that arrives after the request is already in flight", async () => {
    let receivedBody: unknown = null;
    const held: { release: (() => void) | null } = { release: null };
    server.use(
      http.put("/api/portfolio/demo-reset", async ({ request }) => {
        receivedBody = await request.json();
        await new Promise<void>((resolve) => {
          held.release = resolve;
        });
        return HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <ManualResetControl userId={USER_ID} token={TOKEN} version={5} versionObserved />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));
    await waitFor(() => expect(receivedBody).toEqual({ expectedVersion: 5 }));

    // A background refresh lands mid-flight with a newer version — must not alter
    // the request that already left.
    rerender(
      <QueryClientProvider client={client}>
        <ManualResetControl userId={USER_ID} token={TOKEN} version={9} versionObserved />
      </QueryClientProvider>,
    );

    held.release?.();
    await waitFor(() => expect(screen.getByRole("status")).toBeInTheDocument());
    expect(receivedBody).toEqual({ expectedVersion: 5 });
  });
});

describe("ManualResetControl — success", () => {
  it("announces success via a polite live region and replaces the user-scoped portfolio cache from the response", async () => {
    stubMarketPrices();
    server.use(
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 11,
          holdings: [{ id: "h9", assetTicker: "GOOGL", quantity: "3" }],
        }),
      ),
    );

    const client = renderControl(3);
    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    await waitFor(() => {
      const status = screen.getByRole("status");
      expect(status).toHaveAttribute("aria-live", "polite");
      expect(status).toHaveTextContent(/reset/i);
    });

    await waitFor(() => {
      const cached = client.getQueryData<{ version: number; holdings: unknown[] }>(
        portfolioKeys.all(USER_ID),
      );
      expect(cached?.version).toBe(11);
    });
    const cached = client.getQueryData<{ holdings: Array<{ ticker: string }> }>(
      portfolioKeys.all(USER_ID),
    );
    expect(cached?.holdings[0]?.ticker).toBe("GOOGL");
  });

  it("does not present submitted/stale data as the result — the cache reflects the PUT response only", async () => {
    stubMarketPrices();
    server.use(
      http.get("/api/portfolio", () =>
        HttpResponse.json([
          {
            id: "p1",
            userId: USER_ID,
            createdAt: "2026-01-01T00:00:00Z",
            version: 3,
            holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
          },
        ]),
      ),
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 4,
          holdings: [{ id: "h2", assetTicker: "MSFT", quantity: "1" }],
        }),
      ),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(portfolioKeys.all(USER_ID), {
      portfolioId: "p1",
      ownerId: USER_ID,
      version: 3,
      holdings: [{ id: "h1", ticker: "AAPL", quantity: "10" }],
    });

    render(
      <QueryClientProvider client={client}>
        <ManualResetControl userId={USER_ID} token={TOKEN} version={3} versionObserved />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    await waitFor(() => {
      const cached = client.getQueryData<{ holdings: Array<{ ticker: string }> }>(
        portfolioKeys.all(USER_ID),
      );
      expect(cached?.holdings[0]?.ticker).toBe("MSFT");
    });
  });

  it("keeps the control disabled until cache reconciliation has actually finished, not just until the PUT resolves", async () => {
    const held: { release: (() => void) | null } = { release: null };
    server.use(
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 11,
          holdings: [{ id: "h9", assetTicker: "GOOGL", quantity: "3" }],
        }),
      ),
      // Reconciliation's own enrichment fetch — held open so the PUT can settle
      // while reconciliation is still provably in flight.
      http.get("/api/market/prices", async () => {
        await new Promise<void>((resolve) => {
          held.release = resolve;
        });
        return HttpResponse.json([]);
      }),
    );

    const client = renderControl(3);
    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    // The PUT itself has resolved (the server received exactly one request and
    // responded), but reconciliation is deliberately still pending — the button
    // must still read as busy, and the cache must not be updated yet.
    await waitFor(() => expect(held.release).not.toBeNull());
    expect(screen.getByRole("button", { name: /resetting/i })).toBeDisabled();
    expect(client.getQueryData(portfolioKeys.all(USER_ID))).toBeUndefined();

    held.release?.();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled(),
    );
    expect(
      (client.getQueryData(portfolioKeys.all(USER_ID)) as { version: number } | undefined)
        ?.version,
    ).toBe(11);
  });

  it("reconciles the shared portfolio cache even if the component unmounts before the request settles", async () => {
    stubMarketPrices();
    const held: { release: (() => void) | null } = { release: null };
    server.use(
      http.put("/api/portfolio/demo-reset", async () => {
        await new Promise<void>((resolve) => {
          held.release = resolve;
        });
        return HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 7,
          holdings: [{ id: "h5", assetTicker: "TSLA", quantity: "2" }],
        });
      }),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { unmount } = render(
      <QueryClientProvider client={client}>
        <ManualResetControl userId={USER_ID} token={TOKEN} version={2} versionObserved />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));
    await waitFor(() => expect(held.release).not.toBeNull());

    // Navigate away before the in-flight request settles.
    unmount();
    held.release?.();

    // The mutation lives in the QueryClient's MutationCache, independent of the
    // now-unmounted component — reconciliation must still land in the cache
    // both users share.
    await waitFor(() => {
      const cached = client.getQueryData<{ version: number; holdings: Array<{ ticker: string }> }>(
        portfolioKeys.all(USER_ID),
      );
      expect(cached?.version).toBe(7);
    });
    const cached = client.getQueryData<{ holdings: Array<{ ticker: string }> }>(
      portfolioKeys.all(USER_ID),
    );
    expect(cached?.holdings[0]?.ticker).toBe("TSLA");
  });
});

describe("ManualResetControl — conflict (409)", () => {
  it("shows a draft-free conflict notice and hides the primary action, without retrying automatically", async () => {
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/demo-reset", () => {
        putCount += 1;
        return HttpResponse.json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else changed the demo portfolio.",
            currentVersion: 9,
          },
          { status: 409 },
        );
      }),
    );

    renderControl(3);
    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/changed elsewhere/i));
    expect(screen.queryByRole("button", { name: /^reset demo portfolio$/i })).not.toBeInTheDocument();
    expect(putCount).toBe(1);

    // No automatic resubmission using the conflict's own currentVersion.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(putCount).toBe(1);
  });

  it("requires an explicit refresh action before the reset control becomes available again", async () => {
    let getCount = 0;
    server.use(
      http.get("/api/portfolio", () => {
        getCount += 1;
        return HttpResponse.json([
          {
            id: "p1",
            userId: USER_ID,
            createdAt: "2026-01-01T00:00:00Z",
            version: 9,
            holdings: [],
          },
        ]);
      }),
      http.put("/api/portfolio/demo-reset", () =>
        HttpResponse.json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else changed the demo portfolio.",
            currentVersion: 9,
          },
          { status: 409 },
        ),
      ),
    );

    function Harness() {
      const { data } = usePortfolio();
      return (
        <ManualResetControl
          userId={USER_ID}
          token={TOKEN}
          version={data?.version ?? 3}
          versionObserved
        />
      );
    }

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(portfolioKeys.all(USER_ID), {
      portfolioId: "p1",
      ownerId: USER_ID,
      version: 3,
      holdings: [],
    });
    render(
      <QueryClientProvider client={client}>
        <Harness />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());

    const getCallsBeforeRefresh = getCount;
    fireEvent.click(screen.getByRole("button", { name: /refresh/i }));

    // Merely re-observing must never itself trigger a reset.
    await waitFor(() => expect(getCount).toBeGreaterThan(getCallsBeforeRefresh));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("does not clear the conflict state when the re-observation refresh itself fails — a failed refresh is not a genuine re-observation", async () => {
    let getCount = 0;
    let putCount = 0;
    server.use(
      http.get("/api/portfolio", () => {
        getCount += 1;
        // Every refresh attempt fails — the control must stay frozen in conflict,
        // never fall back to treating the still-stale captured version as usable.
        return new HttpResponse(null, { status: 500 });
      }),
      http.put("/api/portfolio/demo-reset", () => {
        putCount += 1;
        return HttpResponse.json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else changed the demo portfolio.",
            currentVersion: 9,
          },
          { status: 409 },
        );
      }),
    );

    function Harness() {
      const { data } = usePortfolio();
      return (
        <ManualResetControl
          userId={USER_ID}
          token={TOKEN}
          version={data?.version ?? 3}
          versionObserved
        />
      );
    }

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(portfolioKeys.all(USER_ID), {
      portfolioId: "p1",
      ownerId: USER_ID,
      version: 3,
      holdings: [],
    });
    render(
      <QueryClientProvider client={client}>
        <Harness />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(putCount).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: /refresh/i }));
    await waitFor(() => expect(getCount).toBe(1));
    // Wait for the reobserve attempt itself to fully settle — its transient
    // "Refreshing…" label also matches /refresh/i, so asserting immediately
    // after the GET fires would race ahead of handleReobserve's own await.
    await waitFor(() => expect(screen.queryByText("Refreshing…")).not.toBeInTheDocument());

    // The refresh failed — conflict must remain: no bare Reset button, no
    // automatic clearing, and no resubmission using the stale captured version.
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^reset demo portfolio$/i })).not.toBeInTheDocument();
    expect(putCount).toBe(1);
    expect(screen.getByRole("button", { name: /refresh & try again/i })).toBeEnabled();

    // A second refresh attempt that succeeds now genuinely clears the conflict.
    server.use(
      http.get("/api/portfolio", () => {
        getCount += 1;
        return HttpResponse.json([
          { id: "p1", userId: USER_ID, createdAt: "2026-01-01T00:00:00Z", version: 9, holdings: [] },
        ]);
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: /refresh/i }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("ManualResetControl — generic failure", () => {
  it.each([401, 403, 429, 503])(
    "shows a usable failure notice on a %i response and leaves the control available for a manual retry",
    async (status) => {
      server.use(
        http.put("/api/portfolio/demo-reset", () => new HttpResponse(null, { status })),
      );

      renderControl(2);
      fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

      await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/failed/i));
      expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled();
    },
  );

  it("does not retry automatically after a network failure", async () => {
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/demo-reset", () => {
        putCount += 1;
        return HttpResponse.error();
      }),
    );

    renderControl(2);
    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(putCount).toBe(1);
  });
});

describe("ManualResetControl — unobserved version (invented-zero guard)", () => {
  it("disables the control and never sends a request when the version was not genuinely observed", async () => {
    let putCount = 0;
    server.use(
      http.put("/api/portfolio/demo-reset", () => {
        putCount += 1;
        return HttpResponse.json({
          id: "p1",
          userId: USER_ID,
          createdAt: "2026-01-01T00:00:00Z",
          version: 1,
          holdings: [],
        });
      }),
    );

    // A defaulted `0` from a backend response that omitted `version` — the exact
    // shape `fetchPortfolio`'s own `?? 0` would have produced.
    renderControl(0, undefined, false);

    const button = screen.getByRole("button", { name: /reset demo portfolio/i });
    expect(button).toBeDisabled();
    fireEvent.click(button);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(putCount).toBe(0);
  });

  it("shows an accessible notice explaining why the control is unavailable, not just a disabled button", () => {
    renderControl(0, undefined, false);

    const button = screen.getByRole("button", { name: /reset demo portfolio/i });
    const describedBy = button.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy ?? "")).toHaveTextContent(/version/i);
  });

  it("enables the control normally once the version is genuinely observed, even when it is zero", () => {
    renderControl(0, undefined, true);
    expect(screen.getByRole("button", { name: /reset demo portfolio/i })).toBeEnabled();
  });
});
