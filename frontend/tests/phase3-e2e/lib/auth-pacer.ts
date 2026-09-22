/**
 * Spaces auth requests (login and signup share one per-IP bucket in Production) so the
 * suite never trips the limiter. The last-request time is persisted to a file so a
 * Playwright worker restart after a failure cannot reset the spacing.
 */
import { readFileSync, writeFileSync } from "node:fs";

export interface PacerStore {
  read(): number | null;
  write(epochMs: number): void;
}

export interface PacerClock {
  now(): number;
  sleep(ms: number): Promise<void>;
}

export const realClock: PacerClock = {
  now: () => Date.now(),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export function fileStore(filePath: string): PacerStore {
  return {
    read() {
      try {
        const parsed = JSON.parse(readFileSync(filePath, "utf8")) as { lastAuthRequestAt?: unknown };
        return typeof parsed.lastAuthRequestAt === "number" ? parsed.lastAuthRequestAt : null;
      } catch {
        return null;
      }
    },
    write(epochMs) {
      writeFileSync(filePath, JSON.stringify({ lastAuthRequestAt: epochMs }));
    },
  };
}

/** Waits until at least `minIntervalMs` has passed since the previous auth request, then records this one. */
export async function paceAuthRequest(
  store: PacerStore,
  minIntervalMs: number,
  clock: PacerClock = realClock,
): Promise<{ waitedMs: number }> {
  let waitedMs = 0;
  const last = store.read();
  if (last !== null && minIntervalMs > 0) {
    const remaining = minIntervalMs - (clock.now() - last);
    if (remaining > 0) {
      await clock.sleep(remaining);
      waitedMs = remaining;
    }
  }
  store.write(clock.now());
  return { waitedMs };
}
