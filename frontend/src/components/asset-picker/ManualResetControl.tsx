"use client";

import { useId, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { portfolioKeys } from "@/lib/hooks/usePortfolio";
import { buildPortfolioResponseFromWireHoldings, fetchPortfolio } from "@/lib/api/portfolio";
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
  /**
   * Whether `version` was genuinely read from the backend's own `version` field,
   * as opposed to a client-side default (`fetchPortfolio`'s `?? 0`) applied
   * because a currently-deployed backend omitted it. `false` blocks the control
   * entirely — never send a version this browser didn't actually observe.
   */
  versionObserved: boolean;
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
export function ManualResetControl({
  userId,
  token,
  version,
  versionObserved,
}: ManualResetControlProps) {
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<Phase>("idle");
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const [isReobserving, setIsReobserving] = useState(false);
  const submittingRef = useRef(false);
  const statusId = useId();
  const alertId = useId();
  const blockedId = useId();

  const mutation = useMutation({
    mutationFn: (expectedVersion: number) => demoReset(token, expectedVersion),
    // No automatic reset retry (requirements.md 7.3b) — a failure surfaces once and
    // waits for the user's own next click, never a library-driven resubmission.
    retry: false,
    // Hook-level callbacks (not per-call `mutate(vars, {onSuccess...})` options):
    // TanStack Query runs these from inside the underlying `Mutation` object
    // itself, which lives in the shared MutationCache independent of any one
    // component's mount state, and genuinely awaits each one before marking the
    // mutation settled. Per-call callbacks are neither of those things — they're
    // skipped outright if this component has already unmounted by the time the
    // request settles (e.g. the user navigated away), and even while mounted
    // they're fire-and-forget, so `isPending` (and this button's disabled state)
    // would flip back to false before this reconciliation actually finishes.
    // Cache writes that other pages/components rely on can't depend on either.
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

  const isSubmitting = mutation.isPending;
  const isBusy = isSubmitting || isReobserving;

  function handleReset() {
    if (submittingRef.current || !versionObserved) return;
    submittingRef.current = true;
    mutation.mutate(version);
  }

  async function handleReobserve() {
    setIsReobserving(true);
    try {
      // Re-observation alone — never a reset. Deliberately a raw fetchPortfolio
      // call (the same one usePortfolio's own queryFn uses), not
      // invalidateQueries/fetchQuery: TanStack Query's default
      // networkMode: "online" marks a refetch "paused" while the browser is
      // offline and — verified against @tanstack/query-core's refetchQueries
      // source — resolves ITS OWN returned promise immediately in that case,
      // without ever attempting the request or waiting for reconnection.
      // `throwOnError` only affects a request that was actually attempted; it
      // cannot fix a request that TanStack Query itself declined to send. A
      // plain fetch has no such pause state — it always attempts the request,
      // so a genuinely offline browser correctly rejects here.
      const freshPortfolio = await fetchPortfolio(userId, token);
      queryClient.setQueryData(portfolioKeys.all(userId), freshPortfolio);
      // Clear the conflict only after a genuinely completed re-observation — a
      // failed (or never-attempted) refresh leaves the browser holding the
      // same already-known-stale version, and clearing here would let the
      // next click resubmit it, or silently queue that resubmission for the
      // moment connectivity returns.
      setPhase("idle");
      setConflictMessage(null);
    } catch {
      // Stay frozen in conflict; the user can press "Refresh & try again" again.
    } finally {
      setIsReobserving(false);
    }
  }

  return (
    <div className="flex flex-col items-end gap-1.5">
      {phase === "conflict" ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          // Local override, not a button.tsx/global-token change (scope
          // discipline — this repo's own outline-button hover treatment,
          // hover:bg-accent hover:text-accent-foreground, resolves to white
          // on the accent green at 2.59:1, verified against the actual
          // --accent/--accent-foreground values and independently confirmed
          // by external review; below WCAG 2.2 1.4.3's 4.5:1 minimum for
          // small text/labels. emerald-700 keeps the existing white hover
          // text at 5.49:1 in both themes.
          className="hover:bg-emerald-700"
          onClick={handleReobserve}
          disabled={isReobserving}
          aria-describedby={alertId}
        >
          {isReobserving ? "Refreshing…" : "Refresh & try again"}
        </Button>
      ) : (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="hover:bg-emerald-700"
          onClick={handleReset}
          disabled={isBusy || !versionObserved}
          aria-describedby={versionObserved ? undefined : blockedId}
        >
          {isSubmitting ? "Resetting…" : "Reset Demo Portfolio"}
        </Button>
      )}

      {!versionObserved && (
        <p id={blockedId} className="text-xs text-muted-foreground">
          Reset is temporarily unavailable until your portfolio&apos;s version has
          been confirmed by the server.
        </p>
      )}

      {/* text-emerald-700/text-red-700/text-red-400 below are chosen, not the
          design system's own text-emerald-600 / text-destructive tokens —
          those measured 3.51-4.32:1 at this 12px/text-xs size against this
          control's actual backgrounds (verified via getComputedStyle + the
          WCAG relative-luminance formula, external review independently
          measured the same), under the 4.5:1 minimum WCAG 2.2 1.4.3 requires
          for text this size. These pass ≥5:1 in both themes without touching
          the shared tokens (used elsewhere for buttons/borders, a different
          contrast situation). dark:text-emerald-400 is unchanged — it
          already measured ~10:1. */}
      {phase === "success" && (
        <p id={statusId} role="status" aria-live="polite" className="text-xs text-emerald-700 dark:text-emerald-400">
          Demo portfolio reset.
        </p>
      )}

      {phase === "conflict" && (
        <p id={alertId} role="alert" className="max-w-[16rem] text-right text-xs text-red-700 dark:text-red-400">
          Your portfolio changed elsewhere. {conflictMessage} Refresh to see the latest
          version before trying again.
        </p>
      )}

      {phase === "failure" && (
        <p role="alert" className="text-xs text-red-700 dark:text-red-400">
          Reset failed. You can try again.
        </p>
      )}
    </div>
  );
}
