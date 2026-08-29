"use client";

import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type { AssetPriceFreshnessDTO } from "../../../types/portfolio";
import { buildFreshnessRows, formatAbsoluteTimestamp } from "./freshnessFormat";

export interface FreshnessDetailsPopoverProps {
  freshness: AssetPriceFreshnessDTO;
}

/**
 * B2 Task 1.17 — anchored popover on the "Details" control, present on both the
 * pre-save and post-save Portfolio page (Task 1.18 handles the re-read; this renders
 * whatever `freshness` it's given).
 *
 * requirements.md 3a frames this as the same focus-transferring popup pattern as the
 * picker modal (Requirement 1.7) — a smaller-scoped instance of it, not a new pattern —
 * so it reuses `@radix-ui/react-dialog` directly rather than a disclosure/tooltip.
 * `modal={false}` since this is a popover, not a page-blocking dialog; Radix's
 * `DialogTrigger` supplies `aria-haspopup="dialog"`/`aria-expanded`/`aria-controls`
 * automatically. Unlike `AssetPickerModal`, no `aria-modal` is added here — that
 * attribute is specific to the page-blocking pattern.
 */
export function FreshnessDetailsPopover({ freshness }: FreshnessDetailsPopoverProps) {
  const rows = buildFreshnessRows(freshness);
  const timestampLabel = formatAbsoluteTimestamp(
    freshness.oldestKnownAssetPriceObservationTimestamp,
  );

  return (
    <DialogPrimitive.Root modal={false}>
      <DialogPrimitive.Trigger asChild>
        <button
          type="button"
          className="text-xs font-semibold text-primary underline underline-offset-2"
        >
          Details
        </button>
      </DialogPrimitive.Trigger>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className="fixed right-4 top-20 z-50 w-72 rounded-lg border border-border bg-popover p-4 text-popover-foreground shadow-lg outline-none"
        >
          <DialogPrimitive.Title className="sr-only">Price freshness details</DialogPrimitive.Title>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wide text-muted-foreground">
              Price freshness
            </span>
            <DialogPrimitive.Close
              aria-label="Close"
              className="rounded p-0.5 hover:bg-muted"
            >
              <X className="h-3.5 w-3.5" />
            </DialogPrimitive.Close>
          </div>
          <ul className="mb-3 flex flex-col gap-1 text-sm">
            {rows.map((row) => (
              <li key={row.label}>
                {row.count === null ? row.label : `${row.label}: ${row.count}`}
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-foreground">{timestampLabel}</p>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
