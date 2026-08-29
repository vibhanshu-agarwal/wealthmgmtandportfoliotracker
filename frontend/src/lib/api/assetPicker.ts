import type { CatalogResponse } from "@/types/assetPicker";
import { apiPath } from "@/lib/config/api";

/**
 * B2 Task 1.11 — the module's own record of the last catalog response, keyed by its
 * `ETag`. This is an in-memory value that lives only for the page session (cleared on
 * reload); it is deliberately NOT a second, persistent cache (no `localStorage` /
 * `IndexedDB`) — design.md D2 forbids exactly that, distinct from ordinary in-memory
 * module state.
 */
let cachedCatalog: { etag: string; response: CatalogResponse } | null = null;

/**
 * B2 Task 1.7/1.11 — fetches the asset catalog for the picker's Browse step, with
 * `ETag`/`If-None-Match` conditional revalidation (design.md D2).
 *
 * Uses a plain `fetch` rather than `fetchWithAuthClient` because that helper treats
 * any non-2xx (including the `304` this function relies on) as a hard failure and
 * gives no access to response headers — the same reason `loadMarketPrices` in
 * `portfolio.ts` bypasses it too.
 */
export async function fetchCatalog(token: string): Promise<CatalogResponse> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
  if (cachedCatalog) {
    headers["If-None-Match"] = cachedCatalog.etag;
  }

  const response = await fetch(apiPath("/assets"), {
    method: "GET",
    headers,
    cache: "no-store",
  });

  if (response.status === 304 && cachedCatalog) {
    // No body on a 304 — reuse the already-held catalog rather than re-fetching or
    // persisting a separate copy.
    return cachedCatalog.response;
  }

  if (!response.ok) {
    throw new Error(`GET /api/assets failed (${response.status})`);
  }

  const data = (await response.json()) as CatalogResponse;
  const etag = response.headers.get("ETag");
  cachedCatalog = etag ? { etag, response: data } : null;
  return data;
}
