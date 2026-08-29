"use client";

import { useId, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { AssetPicker } from "./AssetPicker";
import { hasUnverifiedFidelity } from "./fidelityPreflight";

export interface EditHoldingsButtonProps {
  holdings: AssetHoldingDTO[];
  version: number;
  userId: string;
  token: string;
}

/**
 * B2 Task 1.4 — opens the picker with the current portfolio and version as initial
 * state, behind the `NEXT_PUBLIC_ENABLE_ASSET_PICKER` flag (the caller gates that).
 *
 * Task 1.5 — the data-integrity preflight lives entirely in this click handler, before
 * `AssetPicker` is ever mounted: if any holding carries `quantityFidelityUnverified:
 * true`, the modal SHALL NOT open at all. The refusal notice is a small, non-modal
 * inline notice next to the button, programmatically associated via
 * `aria-describedby` so assistive technology announces it, and never relies on color
 * alone. This preflight is independent of the feature flag — a backend rollback or
 * stale environment can reintroduce unverified data regardless of flag state.
 *
 * Task 1.13 — after the modal closes on a successful save, this button renders the
 * `role="status"`/`aria-live="polite"` post-save confirmation itself, since the modal
 * that produced it has already unmounted by then.
 */
export function EditHoldingsButton({ holdings, version, userId, token }: EditHoldingsButtonProps) {
  const [open, setOpen] = useState(false);
  const [savedAnnouncement, setSavedAnnouncement] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const noticeId = useId();

  const blocked = hasUnverifiedFidelity(holdings);

  return (
    <div className="flex flex-col items-end gap-1.5">
      <Button
        ref={triggerRef}
        type="button"
        aria-describedby={blocked ? noticeId : undefined}
        onClick={() => {
          if (blocked) return;
          setSavedAnnouncement(null);
          setOpen(true);
        }}
      >
        Edit Holdings
      </Button>
      {blocked && (
        <p id={noticeId} className="flex items-center gap-1.5 text-xs text-muted-foreground">
          Editing is temporarily unavailable while some holdings are pending a data
          upgrade.
        </p>
      )}
      {savedAnnouncement && (
        <p role="status" aria-live="polite" className="text-xs text-emerald-600 dark:text-emerald-400">
          {savedAnnouncement}
        </p>
      )}
      <AssetPicker
        open={open}
        onClose={() => setOpen(false)}
        initialHoldings={holdings}
        initialVersion={version}
        userId={userId}
        token={token}
        triggerRef={triggerRef}
        onSaveSuccess={() => setSavedAnnouncement("Holdings saved.")}
      />
    </div>
  );
}
