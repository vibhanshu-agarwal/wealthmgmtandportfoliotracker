"use client";

import { Button } from "@/components/ui/button";
import type { DraftHoldings } from "@/types/assetPicker";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { diffHoldings } from "./reviewDiff";

export interface ReviewStepProps {
  initialHoldings: AssetHoldingDTO[];
  draft: DraftHoldings;
  onBack: () => void;
  onSave: () => void;
  saving: boolean;
}

/**
 * B2 Task 1.12 — pure derivation `diff(initialHoldings, draftHoldings)` →
 * added/changed/removed/unchanged, plus the Save action (Task 1.13 wires the mutation
 * behind `onSave`).
 */
export function ReviewStep({ initialHoldings, draft, onBack, onSave, saving }: ReviewStepProps) {
  const diff = diffHoldings(initialHoldings, draft);

  return (
    <div className="flex flex-col gap-4">
      <p aria-live="polite" className="text-sm text-muted-foreground">
        {draft.size} in draft
      </p>

      <DiffSection label="Added" rows={diff.added} tone="text-emerald-600 dark:text-emerald-400" />
      <DiffSection
        label="Changed"
        rows={diff.changed.map((r) => ({
          ticker: r.ticker,
          name: r.name,
          quantity: `${r.fromQuantity} → ${r.toQuantity}`,
        }))}
        tone="text-blue-600 dark:text-blue-400"
      />
      <DiffSection label="Removed" rows={diff.removed} tone="text-red-600 dark:text-red-400" />
      <DiffSection label="Unchanged" rows={diff.unchanged} tone="text-muted-foreground" />

      <div className="flex justify-between border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onBack} disabled={saving}>
          Back to browse
        </Button>
        <Button type="button" onClick={onSave} disabled={saving}>
          {saving ? "Saving…" : "Save changes"}
        </Button>
      </div>
    </div>
  );
}

function DiffSection({
  label,
  rows,
  tone,
}: {
  label: string;
  rows: Array<{ ticker: string; name: string; quantity: string }>;
  tone: string;
}) {
  if (rows.length === 0) return null;
  return (
    <div>
      <p className={`mb-2 text-xs font-bold uppercase tracking-wide ${tone}`}>
        {label} — {rows.length}
      </p>
      <ul className="flex flex-col gap-1">
        {rows.map((row) => (
          <li key={row.ticker} className="flex items-center justify-between text-sm">
            <span className="font-semibold">{row.ticker}</span>
            <span className={`tabular-nums ${tone}`}>{row.quantity}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
