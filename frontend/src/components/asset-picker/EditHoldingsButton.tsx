"use client";

import { useId, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { AssetPicker } from "./AssetPicker";
import { hasUnverifiedFidelity } from "./fidelityPreflight";

export interface EditHoldingsButtonProps {
  holdings: AssetHoldingDTO[];
  version: number;
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
 */
export function EditHoldingsButton({ holdings, version, token }: EditHoldingsButtonProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const noticeId = useId();

  const blocked = hasUnverifiedFidelity(holdings);

  return (
    <div className="flex flex-col items-start gap-1.5">
      <Button
        ref={triggerRef}
        type="button"
        aria-describedby={blocked ? noticeId : undefined}
        onClick={() => {
          if (blocked) return;
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
      <AssetPicker
        open={open}
        onClose={() => setOpen(false)}
        initialHoldings={holdings}
        initialVersion={version}
        token={token}
        triggerRef={triggerRef}
      />
    </div>
  );
}
