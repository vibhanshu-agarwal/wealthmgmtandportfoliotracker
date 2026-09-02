import { apiPath } from "@/lib/config/api";
import type { WireHolding } from "@/lib/api/portfolio";

export interface DemoResetSuccess {
  status: "success";
  /** Same identity/version-replacement contract as a composition save (requirements.md 4.2). */
  portfolioId: string;
  ownerId: string;
  version: number;
  holdings: WireHolding[];
}

export interface DemoResetConflict {
  status: "conflict";
  currentVersion: number;
  message: string;
}

export type DemoResetResult = DemoResetSuccess | DemoResetConflict;

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
  holdings: WireHolding[];
}

/**
 * B2 Task 6.2 — the manual demo-reset network call against the gateway's
 * `PUT /api/portfolio/demo-reset` route (design.md D5).
 *
 * `expectedVersion` is the caller's already-observed `GET /api/portfolio` version
 * (GC.6) — this function never re-reads or defaults it; a real observed version of
 * zero is sent verbatim. `409 portfolio_version_conflict` is a normal, handled
 * outcome (same discipline as `saveComposition`'s `409`), returned as a typed result
 * rather than thrown. Any other non-2xx, and network failure, throws — the caller
 * surfaces that as a generic failure state and does not retry automatically.
 */
export async function demoReset(token: string, expectedVersion: number): Promise<DemoResetResult> {
  const response = await fetch(apiPath("/portfolio/demo-reset"), {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ expectedVersion }),
    cache: "no-store",
  });

  if (response.status === 409) {
    const body = (await response.json()) as ConflictBody;
    return { status: "conflict", currentVersion: body.currentVersion, message: body.message };
  }

  if (!response.ok) {
    throw new Error(`PUT /api/portfolio/demo-reset failed (${response.status})`);
  }

  const body = (await response.json()) as PortfolioResponseBody;
  return {
    status: "success",
    portfolioId: body.id,
    ownerId: body.userId,
    version: body.version,
    holdings: body.holdings,
  };
}
