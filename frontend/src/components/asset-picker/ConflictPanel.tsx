"use client";

import { useId } from "react";
import { Button } from "@/components/ui/button";
import type { DraftHoldings } from "@/types/assetPicker";

export interface ConflictPanelProps {
  draft: DraftHoldings;
  message: string;
  onReloadAndStartOver: () => void;
  onClose: () => void;
}

/**
 * B2 Task 1.14 — `ConflictPanel`, the frozen state Task 1.13 enters on `409`.
 *
 * Rendered ALONGSIDE a read-only draft summary — not in place of it (design.md D1) —
 * with all rows as non-interactive display elements, not disabled form controls (no
 * `role="checkbox"`/`tabindex`/`aria-disabled` on individual rows). The scroll region
 * itself carries `tabindex="0"` so a keyboard user can reach and scroll it (GC.4,
 * requirements.md 4.3) — that is the one focusable stop, not the rows.
 *
 * Exactly two exits (GC.4): "reload and start over" and closing the modal — both a
 * knowing discard by the user, never automatic on the `409` itself.
 */
export function ConflictPanel({ draft, message, onReloadAndStartOver, onClose }: ConflictPanelProps) {
  const regionLabelId = useId();

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-md border border-destructive/30 bg-destructive/5 p-4">
        <p className="mb-1 text-sm font-semibold text-destructive">
          Your portfolio changed elsewhere
        </p>
        <p className="text-sm text-muted-foreground">
          {message} Your draft below was not saved and can&apos;t be reapplied
          automatically — merging it back in could silently overwrite what they saved.
        </p>
      </div>

      <p id={regionLabelId} className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Your draft (read-only)
      </p>
      <div
        role="region"
        aria-labelledby={regionLabelId}
        tabIndex={0}
        className="max-h-64 overflow-y-auto rounded-md border border-border"
      >
        <ul>
          {Array.from(draft.values(), (entry) => (
            <li
              key={entry.ticker}
              className="flex items-center justify-between border-b border-border px-3 py-2 text-sm last:border-b-0"
            >
              <span className="font-semibold">{entry.ticker}</span>
              <span className="tabular-nums text-muted-foreground">{entry.quantity}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="flex justify-end gap-2.5 border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onClose}>
          Discard &amp; close
        </Button>
        <Button type="button" onClick={onReloadAndStartOver}>
          Reload latest &amp; start over
        </Button>
      </div>
    </div>
  );
}
