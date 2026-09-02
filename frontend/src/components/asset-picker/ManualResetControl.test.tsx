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

function renderControl(version: number, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  render(
    <QueryClientProvider client={client}>
      <ManualResetControl userId={USER_ID} token={TOKEN} version={version} />
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
        <ManualResetControl userId={USER_ID} token={TOKEN} version={5} />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: /reset demo portfolio/i }));
    await waitFor(() => expect(receivedBody).toEqual({ expectedVersion: 5 }));

    // A background refresh lands mid-flight with a newer version — must not alter
    // the request that already left.
    rerender(
      <QueryClientProvider client={client}>
        <ManualResetControl userId={USER_ID} token={TOKEN} version={9} />
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
        <ManualResetControl userId={USER_ID} token={TOKEN} version={3} />
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
      return <ManualResetControl userId={USER_ID} token={TOKEN} version={data?.version ?? 3} />;
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
