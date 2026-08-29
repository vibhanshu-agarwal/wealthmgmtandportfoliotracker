"use client";

import { useState, type RefObject } from "react";
import type { AssetHoldingDTO } from "@/types/portfolio";
import type { DraftHoldings } from "@/types/assetPicker";
import { useCatalog } from "@/lib/hooks/useCatalog";
import { AssetPickerModal } from "./AssetPickerModal";
import { BrowseStep } from "./BrowseStep";
import { seedDraftFromHoldings } from "./draftState";

export interface AssetPickerProps {
  open: boolean;
  onClose: () => void;
  /** The source read this draft is seeded from — already confirmed all-verified (Task 1.5). */
  initialHoldings: AssetHoldingDTO[];
  /** `expectedVersion` this session's save will carry (Task 1.2/1.13, wired in Checkpoint 3). */
  initialVersion: number;
  token: string;
  triggerRef: RefObject<HTMLButtonElement | null>;
}

interface DraftSeed {
  draft: DraftHoldings;
  initialQuantities: Map<string, string>;
}

const EMPTY_SEED: DraftSeed = { draft: new Map(), initialQuantities: new Map() };

/**
 * B2's orchestrating component: owns draft state (Task 1.6), seeds it per GC.1, and
 * composes the modal shell with Browse. Review/Save/ConflictPanel/PresenceBanner are
 * added in Checkpoint 3 — this checkpoint's save action is not wired yet.
 */
export function AssetPicker({
  open,
  onClose,
  initialHoldings,
  initialVersion,
  token,
  triggerRef,
}: AssetPickerProps) {
  // Task 1.2/1.13: threaded through, not yet consumed — the save mutation wiring
  // (Checkpoint 3) is what reads this to build expectedVersion.
  void initialVersion;
  const catalogQuery = useCatalog(token, open);

  const [seed, setSeed] = useState<DraftSeed>(EMPTY_SEED);
  // Tracks whether `seed` already reflects the current open session, so a later
  // catalog re-render (e.g. background refetch) never silently overwrites an
  // in-progress draft.
  const [seededForOpen, setSeededForOpen] = useState(false);

  // GC.1: seed the draft fully, once per open, from whatever the catalog resolved to
  // by then. Adjusting state during render (React's documented alternative to an
  // effect for "state derived from a prop/query change") rather than in a useEffect —
  // React re-renders immediately with the corrected state before the browser paints.
  if (!open && seededForOpen) {
    setSeededForOpen(false);
  } else if (open && !seededForOpen && !catalogQuery.isLoading) {
    const catalogAssets = catalogQuery.data?.assets ?? [];
    setSeed({
      draft: seedDraftFromHoldings(initialHoldings, catalogAssets),
      initialQuantities: new Map(initialHoldings.map((h) => [h.ticker, h.quantity])),
    });
    setSeededForOpen(true);
  }

  return (
    <AssetPickerModal open={open} onClose={onClose} triggerRef={triggerRef}>
      {catalogQuery.isLoading ? (
        <p className="py-8 text-center text-sm text-muted-foreground">Loading catalog…</p>
      ) : (
        <BrowseStep
          catalog={catalogQuery.data?.assets ?? []}
          draft={seed.draft}
          onDraftChange={(nextDraft) => setSeed((prev) => ({ ...prev, draft: nextDraft }))}
          initialQuantities={seed.initialQuantities}
        />
      )}
    </AssetPickerModal>
  );
}
