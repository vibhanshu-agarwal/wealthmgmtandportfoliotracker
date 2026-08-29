export interface PresenceBannerProps {
  anotherSessionActive: boolean;
}

/**
 * B2 Task 1.15 — queried once on mount by the caller via `usePresence`; renders the
 * persistent advisory on `anotherSessionActive: true`, and nothing on error/absence
 * (GC.5) — never blocks, delays, or alters the underlying request.
 */
export function PresenceBanner({ anotherSessionActive }: PresenceBannerProps) {
  if (!anotherSessionActive) return null;

  return (
    <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-6 py-2.5 text-xs text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200">
      Another demo session is active — your changes may not save.
    </div>
  );
}
