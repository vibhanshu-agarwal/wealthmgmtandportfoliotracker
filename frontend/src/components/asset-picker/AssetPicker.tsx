"use client";

import { useState, type RefObject } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { AssetHoldingDTO } from "@/types/portfolio";
import type { DraftHoldings } from "@/types/assetPicker";
import { useCatalog } from "@/lib/hooks/useCatalog";
import { usePresence } from "@/lib/hooks/usePresence";
import { portfolioKeys } from "@/lib/hooks/usePortfolio";
import { saveComposition } from "@/lib/api/assetPickerSave";
import { AssetPickerModal } from "./AssetPickerModal";
import { BrowseStep } from "./BrowseStep";
import { ReviewStep } from "./ReviewStep";
import { ConflictPanel } from "./ConflictPanel";
import { PresenceBanner } from "./PresenceBanner";
import { StepIndicator, type PickerStep } from "./StepIndicator";
import { Button } from "@/components/ui/button";
import { seedDraftFromHoldings } from "./draftState";
import { buildSavePayload } from "./savePayload";

export interface AssetPickerProps {
  open: boolean;
  onClose: () => void;
  /** The source read this draft is seeded from — already confirmed all-verified (Task 1.5). */
  initialHoldings: AssetHoldingDTO[];
  /** `expectedVersion` this session's save will carry (Task 1.2/GC.6) — captured at
   *  open time and never re-read for the save itself. */
  initialVersion: number;
  /** Authenticated caller's id, for the portfolio query-cache key (Task 1.13). */
  userId: string;
  token: string;
  triggerRef: RefObject<HTMLButtonElement | null>;
  /** Fires after a successful save, once the modal has already closed (Task 1.13). */
  onSaveSuccess?: () => void;
}

interface DraftSeed {
  draft: DraftHoldings;
  initialQuantities: Map<string, string>;
}

const EMPTY_SEED: DraftSeed = { draft: new Map(), initialQuantities: new Map() };

/**
 * B2's orchestrating component: owns draft state (Task 1.6) and the
 * Browse/Review/Save state machine (Tasks 1.10-1.15).
 */
export function AssetPicker({
  open,
  onClose,
  initialHoldings,
  initialVersion,
  userId,
  token,
  triggerRef,
  onSaveSuccess,
}: AssetPickerProps) {
  const catalogQuery = useCatalog(token, open);
  const presence = usePresence(token, open);
  const queryClient = useQueryClient();

  const [seed, setSeed] = useState<DraftSeed>(EMPTY_SEED);
  const [seededForOpen, setSeededForOpen] = useState(false);
  const [step, setStep] = useState<PickerStep>("browse");
  const [conflict, setConflict] = useState<{ currentVersion: number; message: string } | null>(
    null,
  );

  const saveMutation = useMutation({
    mutationFn: () => saveComposition(token, buildSavePayload(seed.draft, initialVersion)),
  });

  // GC.1: seed the draft fully, once per open, from whatever the catalog resolved to
  // by then. Adjusting state during render (React's documented alternative to an
  // effect for "state derived from a prop/query change") rather than in a useEffect.
  if (!open && seededForOpen) {
    setSeededForOpen(false);
    setStep("browse");
    setConflict(null);
  } else if (open && !seededForOpen && !catalogQuery.isLoading) {
    const catalogAssets = catalogQuery.data?.assets ?? [];
    setSeed({
      draft: seedDraftFromHoldings(initialHoldings, catalogAssets),
      initialQuantities: new Map(initialHoldings.map((h) => [h.ticker, h.quantity])),
    });
    setSeededForOpen(true);
  }

  function handleSave() {
    // GC.6: the mutation is a pure PUT, never preceded by its own version-observing
    // read — `initialVersion` was already captured at modal-open time (AssetPicker's
    // own prop), not re-read here.
    saveMutation.mutate(undefined, {
      onSuccess: (result) => {
        if (result.status === "conflict") {
          // GC.4: the draft stays visible, frozen, until the user's explicit action —
          // never an automatic reapply, merge, resubmit, or discard.
          setConflict({ currentVersion: result.currentVersion, message: result.message });
          setStep("conflict" as PickerStep);
          return;
        }
        // Task 1.13 success transition: reconcile the query caches — a post-success
        // read, a separate concern from GC.6's zero-GET-during-the-mutation rule —
        // then close via the same path Escape already uses, restoring focus.
        queryClient.invalidateQueries({ queryKey: portfolioKeys.all(userId) });
        queryClient.invalidateQueries({ queryKey: portfolioKeys.summary(userId) });
        queryClient.invalidateQueries({ queryKey: portfolioKeys.analytics(userId) });
        onClose();
        onSaveSuccess?.();
      },
    });
  }

  function handleReloadAndStartOver() {
    queryClient.invalidateQueries({ queryKey: portfolioKeys.all(userId) });
    onClose();
  }

  const isConflict = step === ("conflict" as PickerStep);

  return (
    <AssetPickerModal
      open={open}
      onClose={onClose}
      triggerRef={triggerRef}
      banner={<PresenceBanner anotherSessionActive={presence.anotherSessionActive} />}
      stepIndicator={!isConflict ? <StepIndicator current={step} /> : undefined}
    >
      {isConflict && conflict ? (
        <ConflictPanel
          draft={seed.draft}
          message={conflict.message}
          onReloadAndStartOver={handleReloadAndStartOver}
          onClose={onClose}
        />
      ) : catalogQuery.isLoading ? (
        <p className="py-8 text-center text-sm text-muted-foreground">Loading catalog…</p>
      ) : step === "review" ? (
        <ReviewStep
          initialHoldings={initialHoldings}
          draft={seed.draft}
          onBack={() => setStep("browse")}
          onSave={handleSave}
          saving={saveMutation.isPending}
        />
      ) : (
        <div className="flex flex-col gap-4">
          <BrowseStep
            catalog={catalogQuery.data?.assets ?? []}
            draft={seed.draft}
            onDraftChange={(nextDraft) => setSeed((prev) => ({ ...prev, draft: nextDraft }))}
            initialQuantities={seed.initialQuantities}
            token={token}
          />
          <div className="flex justify-end border-t border-border pt-4">
            <Button type="button" onClick={() => setStep("review")}>
              Review changes
            </Button>
          </div>
        </div>
      )}
    </AssetPickerModal>
  );
}
