"use client";

import type { ReactNode, RefObject } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

export interface AssetPickerModalProps {
  open: boolean;
  /**
   * Fires on every close path this shell owns — Escape, the close button, or an
   * outside click — with the same discard semantics (requirements.md 1.5/1.7).
   * A successful save (Task 1.13) closes via this same callback, not a separate path.
   */
  onClose: () => void;
  children: ReactNode;
  /** Rendered above the step content — Task 1.15's PresenceBanner slot (Checkpoint 3). */
  banner?: ReactNode;
  /** Rendered below the header — Browse/Review/Save step indicator (Checkpoint 2/3). */
  stepIndicator?: ReactNode;
  /**
   * Ref to the button that opens this modal (`EditHoldingsButton`). Focus returns here
   * on close, by whichever path closes it — close button, `Escape`, or a successful
   * save (requirements.md 1.7).
   *
   * This is not wired through Radix's own `DialogTrigger`, since `EditHoldingsButton`
   * owns a preflight check (Task 1.5) that must run before the modal opens at all —
   * `DialogTrigger`'s automatic open-on-click would fight that gate. Radix's own
   * close-autofocus only restores focus to a `DialogTrigger`-registered element, so
   * without this the trigger ref is null and focus falls back to the document body.
   */
  triggerRef?: RefObject<HTMLButtonElement | null>;
}

/**
 * B2 Task 1.3 — `AssetPickerModal` shell.
 *
 * Implements the WAI-ARIA APG Dialog (Modal) pattern via `@radix-ui/react-dialog`
 * (no existing Dialog primitive to reuse — design.md D1): `role="dialog"`,
 * `aria-modal="true"`, `aria-labelledby` pointing at the visible "Edit Holdings"
 * heading, a trapped and restored focus, and `Escape`/close-button discard.
 *
 * This is the dumb shell only — draft state, steps, and the save/conflict state
 * machine live in the orchestrating `AssetPicker` component that renders this.
 */
export function AssetPickerModal({
  open,
  onClose,
  children,
  banner,
  stepIndicator,
  triggerRef,
}: AssetPickerModalProps) {
  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onClose();
      }}
    >
      <DialogContent
        className="flex max-h-[85vh] max-w-2xl flex-col gap-0 p-0"
        onCloseAutoFocus={(event) => {
          if (triggerRef?.current) {
            event.preventDefault();
            triggerRef.current.focus();
          }
        }}
      >
        <DialogHeader className="border-b px-6 py-5 text-left">
          <DialogTitle>Edit Holdings</DialogTitle>
          <DialogDescription>
            Browse assets and build your desired portfolio.
          </DialogDescription>
        </DialogHeader>
        {banner}
        {stepIndicator}
        <div className="flex-1 overflow-y-auto px-6 py-4">{children}</div>
      </DialogContent>
    </Dialog>
  );
}
