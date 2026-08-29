import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { useCatalog } from "./useCatalog";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useCatalog", () => {
  it("does not fetch when disabled", () => {
    let called = false;
    server.use(
      http.get("/api/assets", () => {
        called = true;
        return HttpResponse.json({ catalogVersion: "v1", assets: [] });
      }),
    );

    renderHook(() => useCatalog("token", false), { wrapper });
    expect(called).toBe(false);
  });

  it("fetches the catalog when enabled", async () => {
    server.use(
      http.get("/api/assets", () =>
        HttpResponse.json({
          catalogVersion: "v1",
          assets: [
            { ticker: "AAPL", name: "Apple", aliases: [], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
          ],
        }),
      ),
    );

    const { result } = renderHook(() => useCatalog("token", true), { wrapper });

    await waitFor(() => expect(result.current.data?.assets).toHaveLength(1));
  });
});
