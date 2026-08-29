import { apiPath } from "@/lib/config/api";
import type { SavePayload } from "@/components/asset-picker/savePayload";

export interface SaveSuccess {
  status: "success";
  version: number;
  holdings: Array<{ assetTicker: string; quantity: string }>;
}

export interface SaveConflict {
  status: "conflict";
  currentVersion: number;
  message: string;
}

export type SaveResult = SaveSuccess | SaveConflict;

interface ConflictBody {
  error: "portfolio_version_conflict";
  message: string;
  currentVersion: number;
}

interface PortfolioResponseBody {
  id: string;
  userId: string;
  createdAt: string;
  version: number;
  holdings: Array<{ id: string; assetTicker: string; quantity: string }>;
}

/**
 * B2 Task 1.13 — the composition-save network call against B1's frozen
 * `PUT /api/portfolio/holdings` contract (design.md D2).
 *
 * `409 portfolio_version_conflict` is a normal, handled outcome (GC.4) — returned as a
 * typed result, not thrown — so the caller's state machine can enter the frozen
 * conflict state without a try/catch. Any other non-2xx is a genuine failure and
 * throws, same as the rest of the codebase's fetch wrappers.
 */
export async function saveComposition(token: string, payload: SavePayload): Promise<SaveResult> {
  const response = await fetch(apiPath("/portfolio/holdings"), {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
    cache: "no-store",
  });

  if (response.status === 409) {
    const body = (await response.json()) as ConflictBody;
    return { status: "conflict", currentVersion: body.currentVersion, message: body.message };
  }

  if (!response.ok) {
    throw new Error(`PUT /api/portfolio/holdings failed (${response.status})`);
  }

  const body = (await response.json()) as PortfolioResponseBody;
  return {
    status: "success",
    version: body.version,
    holdings: body.holdings.map((h) => ({ assetTicker: h.assetTicker, quantity: h.quantity })),
  };
}
