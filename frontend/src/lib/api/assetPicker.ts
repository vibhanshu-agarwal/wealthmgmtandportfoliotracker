import type { CatalogResponse } from "@/types/assetPicker";
import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";
import { apiPath } from "@/lib/config/api";

/**
 * B2 Task 1.7 — fetches the full asset catalog for the picker's Browse step.
 *
 * Task 1.11 (Checkpoint 3) extends this with `ETag`/`If-None-Match` conditional
 * revalidation; this call is the plain, always-`200` shape Browse is first built
 * against.
 */
export async function fetchCatalog(token: string): Promise<CatalogResponse> {
  return fetchWithAuthClient<CatalogResponse>(apiPath("/assets"), token);
}
