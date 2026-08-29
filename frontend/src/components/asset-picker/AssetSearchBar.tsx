"use client";

export interface AssetSearchBarProps {
  value: string;
  onChange: (value: string) => void;
}

/** B2 Task 1.7 — client-side filter by ticker or name (requirements.md 2.1). */
export function AssetSearchBar({ value, onChange }: AssetSearchBarProps) {
  return (
    <input
      type="search"
      aria-label="Search assets by ticker or name"
      placeholder="Search by ticker or name"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
    />
  );
}
