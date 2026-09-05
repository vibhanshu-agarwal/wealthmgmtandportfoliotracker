"use client";

import { useState, type RefObject } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { AssetHoldingDTO } from "@/types/portfolio";
import type { DraftHoldings } from "@/types/assetPicker";
import { useCatalog } from "@/lib/hooks/useCatalog";
import { usePresence } from "@/lib/hooks/usePresence";
import { portfolioKeys } from "@/lib/hooks/usePortfolio";
import { buildPortfolioResponseFromWireHoldings } from "@/lib/api/portfolio";
import { saveComposition } from "@/lib/api/assetPickerSave";
import { AssetPickerModal } from "./AssetPickerModal";
import { BrowseStep } from "./BrowseStep";
import { ReviewStep } from "./ReviewStep";
import { ConflictPanel } from "./ConflictPanel";
import { PresenceBanner } from "./PresenceBanner";
import { StepIndicator, type PickerStep } from "./StepIndicator";
import { Button } from "@/components/ui/button";
import { seedDraftFromHoldings } from "./draftState";
import { isDraftValid } from "./draftValidity";
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
  /**
   * `initialHoldings`/`initialVersion` frozen at the moment the draft was seeded —
   * never the live prop afterward. `EditHoldingsButton` passes whatever
   * `usePortfolio()` currently holds, which refetches every 60s (Task 1.13's own
   * review-fix note); without this snapshot, a background refresh landing while the
   * modal is open would silently swap in a newer `expectedVersion` and a newer
   * review baseline mid-session — defeating GC.6's "captured at open time, never
   * re-read" rule and corrupting the Review step's diff.
   */
  initialHoldingsAtOpen: AssetHoldingDTO[];
  initialVersionAtOpen: number;
}

const EMPTY_SEED: DraftSeed = {
  draft: new Map(),
  initialQuantities: new Map(),
  initialHoldingsAtOpen: [],
  initialVersionAtOpen: 0,
};

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
  // Task 9.1 — a catalog fetch that has never succeeded must not read as an empty,
  // apparently-healthy Browse list. A *background* revalidation failure with an
  // already-held catalog (`catalogQuery.data` still set) is deliberately excluded —
  // that stays silent and self-heals, per the existing staleTime/refetch policy.
  const catalogUnavailable = catalogQuery.isError && !catalogQuery.data;
  const queryClient = useQueryClient();

  const [seed, setSeed] = useState<DraftSeed>(EMPTY_SEED);
  const [seededForOpen, setSeededForOpen] = useState(false);
  // Task 1.15 (review-fix): identifies this open session for usePresence, so a
  // second open queries presence again rather than serving the first open's
  // staleTime:Infinity result forever — AssetPicker itself never unmounts between
  // opens (EditHoldingsButton only toggles its `open` prop).
  const [openGeneration, setOpenGeneration] = useState(0);
  const presence = usePresence(token, open, openGeneration);
  const [step, setStep] = useState<PickerStep>("browse");
  const [conflict, setConflict] = useState<{ currentVersion: number; message: string } | null>(
    null,
  );

  const saveMutation = useMutation({
    mutationFn: () =>
      saveComposition(token, buildSavePayload(seed.draft, seed.initialVersionAtOpen)),
  });

  // GC.1: seed the draft fully, once per open, from whatever the catalog resolved to
  // by then. Adjusting state during render (React's documented alternative to an
  // effect for "state derived from a prop/query change") rather than in a useEffect.
  if (!open && seededForOpen) {
    setSeededForOpen(false);
    setStep("browse");
    setConflict(null);
  } else if (open && !seededForOpen && !catalogQuery.isLoading && !catalogUnavailable) {
    setOpenGeneration((prev) => prev + 1);
    const catalogAssets = catalogQuery.data?.assets ?? [];
    setSeed({
      draft: seedDraftFromHoldings(initialHoldings, catalogAssets),
      initialQuantities: new Map(initialHoldings.map((h) => [h.ticker, h.quantity])),
      initialHoldingsAtOpen: initialHoldings,
      initialVersionAtOpen: initialVersion,
    });
    setSeededForOpen(true);
  }

  function handleSave() {
    // GC.6: the mutation is a pure PUT, never preceded by its own version-observing
    // read — `initialVersion` was already captured at modal-open time (AssetPicker's
    // own `seed` snapshot), not re-read here.
    saveMutation.mutate(undefined, {
      onSuccess: async (result) => {
        if (result.status === "conflict") {
          // GC.4: the draft stays visible, frozen, until the user's explicit action —
          // never an automatic reapply, merge, resubmit, or discard.
          setConflict({ currentVersion: result.currentVersion, message: result.message });
          setStep("conflict" as PickerStep);
          return;
        }
        // requirements.md 4.2 / Task 1.13: replace the visible portfolio state with
        // the PUT response body's actual holdings and version — never the client's
        // own draft, and never dependent on a subsequent GET succeeding. This uses
        // the exact same enrichment fetchPortfolio's own GET path uses, so the
        // cached shape is indistinguishable from what a GET would have produced.
        try {
          const portfolioFromResponse = await buildPortfolioResponseFromWireHoldings(
            { portfolioId: result.portfolioId, ownerId: result.ownerId, version: result.version },
            result.holdings,
            token,
          );
          queryClient.setQueryData(portfolioKeys.all(userId), portfolioFromResponse);
        } catch {
          // Enrichment (a market-price read) failed — fall back to invalidation
          // below rather than leaving the cache holding pre-save data with no
          // path to recover it.
          await queryClient.invalidateQueries({ queryKey: portfolioKeys.all(userId) });
        }

        // summary/analytics carry backend-computed figures (unrealized P&L, freshness)
        // the PUT response has no way to provide — these genuinely need a read, which
        // Task 1.18 already covers. Awaited, not fire-and-forget, so the modal doesn't
        // close and announce success before that read has been given a chance to land;
        // a reconciliation failure here still closes (the PUT already succeeded) but
        // no longer races ahead of a healthy one.
        try {
          await Promise.all([
            queryClient.invalidateQueries({ queryKey: portfolioKeys.summary(userId) }),
            queryClient.invalidateQueries({ queryKey: portfolioKeys.analytics(userId) }),
          ]);
        } catch {
          // staleTime/refetchInterval on the affected queries will self-heal.
        }
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
  // Task 1.8 (review-fix): Review changes never opens onto an invalid draft — an
  // invalid quantity blocks progression here, not just at submit time.
  const draftValid = isDraftValid(seed.draft, seed.initialQuantities);

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
      ) : catalogUnavailable ? (
        <div role="alert" className="flex flex-col items-center gap-3 py-8 text-center">
          <p className="text-sm text-destructive">
            Couldn&apos;t load the asset catalog. Nothing can be reviewed or saved until it
            loads.
          </p>
          <Button type="button" variant="outline" onClick={() => catalogQuery.refetch()}>
            Retry
          </Button>
        </div>
      ) : step === "review" ? (
        <ReviewStep
          initialHoldings={seed.initialHoldingsAtOpen}
          draft={seed.draft}
          onBack={() => setStep("browse")}
          onSave={handleSave}
          saving={saveMutation.isPending}
        />
      ) : (
        <div
          className="flex flex-col gap-4"
          // Task 9.1 — `dataUpdatedAt` only advances when the catalog query's
          // queryFn actually resolves into a new *successful* state (react-query
          // updates it on every settled fetch, including one whose 304 branch
          // just returns the retained cachedCatalog — never on error, and never
          // merely because a network response arrived). The real-stack browser
          // spec asserts this changes after a 304, since "Browse still shows
          // data" alone can't distinguish a successful revalidation from a
          // failed one that silently kept the previous data (this component
          // deliberately shows no error for a background failure with an
          // already-held catalog).
          data-catalog-updated-at={catalogQuery.dataUpdatedAt}
        >
          <BrowseStep
            catalog={catalogQuery.data?.assets ?? []}
            draft={seed.draft}
            onDraftChange={(nextDraft) => setSeed((prev) => ({ ...prev, draft: nextDraft }))}
            initialQuantities={seed.initialQuantities}
            token={token}
          />
          <div className="flex justify-end border-t border-border pt-4">
            <Button type="button" onClick={() => setStep("review")} disabled={!draftValid}>
              Review changes
            </Button>
          </div>
        </div>
      )}
    </AssetPickerModal>
  );
}
