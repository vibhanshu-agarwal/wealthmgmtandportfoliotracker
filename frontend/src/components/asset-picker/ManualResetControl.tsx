"use client";

import { useId, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { portfolioKeys } from "@/lib/hooks/usePortfolio";
import { buildPortfolioResponseFromWireHoldings } from "@/lib/api/portfolio";
import { demoReset } from "@/lib/api/demoReset";

export interface ManualResetControlProps {
  userId: string;
  token: string;
  /**
   * The Portfolio_Version last observed by this browser (GC.6). Read only inside
   * the click handler, at the moment a reset is requested — a later change to this
   * prop (a background refresh landing mid-flight) never alters an already-sent
   * request, because the value is captured into the mutation call by copy, not by
   * live reference.
   */
  version: number;
}

type Phase = "idle" | "success" | "conflict" | "failure";

/**
 * B2 Tasks 6.1/6.2 — a hidden, independently-flagged manual demo-reset control.
 *
 * Temporary host (documented placement, not a product decision): mounted page-level
 * in `PortfolioPageContent`, outside the picker. requirements.md 7.6's final
 * placement is still OPEN; because this host has no open picker draft, a `409`
 * surfaces here as a draft-free notice rather than `ConflictPanel` — same envelope,
 * same no-retry rule (requirements.md 7.3b). Relocating this control later —
 * including into the picker, where a `409` would instead need `ConflictPanel`'s
 * draft-preserving contract — does not require re-plumbing `demoReset` itself.
 */
export function ManualResetControl({ userId, token, version }: ManualResetControlProps) {
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<Phase>("idle");
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const [isReobserving, setIsReobserving] = useState(false);
  const submittingRef = useRef(false);
  const statusId = useId();
  const alertId = useId();

  const mutation = useMutation({
    mutationFn: (expectedVersion: number) => demoReset(token, expectedVersion),
    // No automatic reset retry (requirements.md 7.3b) — a failure surfaces once and
    // waits for the user's own next click, never a library-driven resubmission.
    retry: false,
  });

  const isSubmitting = mutation.isPending;
  const isBusy = isSubmitting || isReobserving;

  function handleReset() {
    if (submittingRef.current) return;
    submittingRef.current = true;
    const expectedVersion = version;

    mutation.mutate(expectedVersion, {
      onSuccess: async (result) => {
        if (result.status === "conflict") {
          // GC.4-equivalent for this draft-free host: freeze here, no automatic
          // resubmission using result.currentVersion — only an explicit
          // re-observation (handleReobserve) can clear this state.
          setPhase("conflict");
          setConflictMessage(result.message);
          return;
        }

        setPhase("success");
        setConflictMessage(null);

        // requirements.md 4.2-equivalent: replace visible state from the PUT
        // response body itself, the same enrichment a GET would apply — never the
        // stale pre-reset cache and never dependent on a later GET succeeding.
        try {
          const portfolioFromResponse = await buildPortfolioResponseFromWireHoldings(
            { portfolioId: result.portfolioId, ownerId: result.ownerId, version: result.version },
            result.holdings,
            token,
          );
          queryClient.setQueryData(portfolioKeys.all(userId), portfolioFromResponse);
        } catch {
          await queryClient.invalidateQueries({ queryKey: portfolioKeys.all(userId) });
        }

        try {
          await Promise.all([
            queryClient.invalidateQueries({ queryKey: portfolioKeys.summary(userId) }),
            queryClient.invalidateQueries({ queryKey: portfolioKeys.analytics(userId) }),
          ]);
        } catch {
          // staleTime/refetchInterval on the affected queries will self-heal.
        }
      },
      onError: () => {
        setPhase("failure");
        setConflictMessage(null);
      },
      onSettled: () => {
        submittingRef.current = false;
      },
    });
  }

  async function handleReobserve() {
    setIsReobserving(true);
    try {
      // Re-observation alone — never a reset. This only refreshes the portfolio
      // query so a fresh `version` reaches this control via its own prop.
      await queryClient.invalidateQueries({ queryKey: portfolioKeys.all(userId) });
    } finally {
      setIsReobserving(false);
      setPhase("idle");
      setConflictMessage(null);
    }
  }

  return (
    <div className="flex flex-col items-end gap-1.5">
      {phase === "conflict" ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleReobserve}
          disabled={isReobserving}
          aria-describedby={alertId}
        >
          {isReobserving ? "Refreshing…" : "Refresh & try again"}
        </Button>
      ) : (
        <Button type="button" variant="outline" size="sm" onClick={handleReset} disabled={isBusy}>
          {isSubmitting ? "Resetting…" : "Reset Demo Portfolio"}
        </Button>
      )}

      {phase === "success" && (
        <p id={statusId} role="status" aria-live="polite" className="text-xs text-emerald-600 dark:text-emerald-400">
          Demo portfolio reset.
        </p>
      )}

      {phase === "conflict" && (
        <p id={alertId} role="alert" className="max-w-[16rem] text-right text-xs text-destructive">
          Your portfolio changed elsewhere. {conflictMessage} Refresh to see the latest
          version before trying again.
        </p>
      )}

      {phase === "failure" && (
        <p role="alert" className="text-xs text-destructive">
          Reset failed. You can try again.
        </p>
      )}
    </div>
  );
}
