"use client";

import { useId } from "react";
import { cn } from "@/lib/utils/cn";
import type { AssetLifecycleStatus } from "@/types/assetPicker";

export interface DraftRowProps {
  ticker: string;
  name: string;
  quantity: string;
  checked: boolean;
  lifecycleStatus: AssetLifecycleStatus;
  onToggle: (ticker: string) => void;
  onQuantityChange: (ticker: string, value: string) => void;
  /** Validation message to associate via `aria-describedby` — never color alone. */
  errorMessage?: string;
}

/**
 * B2 Task 1.7 — one drafted holding row, with the `RetainedDeprecatedRow` variant
 * folded in via `lifecycleStatus` (design.md D1 draws these as one component, not two).
 *
 * requirements.md 1.8 control semantics:
 *  - the selection control is `role="checkbox"` with a live `aria-checked` and an
 *    `aria-label` naming the asset ("Select AAPL");
 *  - the quantity input carries its own `aria-label` ("AAPL quantity");
 *  - a deprecated row's checkbox stays fully operable — no `aria-disabled` — since
 *    deselecting it is how it's removed; the reduce-or-remove-only constraint applies
 *    to the quantity input, surfaced via `aria-describedby` plus `max` as a
 *    supplementary hint, never primary enforcement (native `max` is inert on this
 *    text-mode `inputmode="decimal"` control — real enforcement is
 *    `validateRetainedDeprecatedQuantity`, applied by the caller, which is why this
 *    component still forwards a rejected value up via `onQuantityChange` rather than
 *    blocking the keystroke).
 */
export function DraftRow({
  ticker,
  name,
  quantity,
  checked,
  lifecycleStatus,
  onToggle,
  onQuantityChange,
  errorMessage,
}: DraftRowProps) {
  const errorId = useId();
  const deprecatedHintId = useId();
  const isDeprecated = lifecycleStatus === "DEPRECATED";

  const describedBy = [
    errorMessage ? errorId : null,
    isDeprecated ? deprecatedHintId : null,
  ]
    .filter(Boolean)
    .join(" ") || undefined;

  return (
    <div
      className="flex items-center gap-3 rounded-md border border-border px-3 py-2"
      data-ticker={ticker}
    >
      <button
        type="button"
        role="checkbox"
        aria-checked={checked}
        aria-label={`Select ${ticker}`}
        onClick={() => onToggle(ticker)}
        className={cn(
          "flex h-5 w-5 shrink-0 items-center justify-center rounded border border-input",
          checked && "border-primary bg-primary text-primary-foreground",
        )}
      >
        {checked && (
          <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true">
            <path
              d="M3 8.5 6.5 12 13 4"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </button>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold">{ticker}</span>
          {isDeprecated && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-semibold text-muted-foreground">
              No longer offered
            </span>
          )}
        </div>
        <p className="truncate text-xs text-muted-foreground">{name}</p>
      </div>

      <input
        type="text"
        inputMode="decimal"
        aria-label={`${ticker} quantity`}
        aria-describedby={describedBy}
        aria-invalid={errorMessage ? true : undefined}
        value={quantity}
        max={isDeprecated ? quantity : undefined}
        onChange={(event) => onQuantityChange(ticker, event.target.value)}
        className="h-9 w-28 rounded-md border border-input bg-background px-2 text-right text-sm tabular-nums"
      />

      {errorMessage && (
        <span id={errorId} className="sr-only">
          {errorMessage}
        </span>
      )}
      {isDeprecated && (
        <span id={deprecatedHintId} className="sr-only">
          This asset is no longer offered. You can reduce or remove this holding, but not
          increase it.
        </span>
      )}
    </div>
  );
}
