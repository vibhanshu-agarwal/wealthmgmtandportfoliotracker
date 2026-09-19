/**
 * Wave 10.2 Step B (exit criterion 5b) - bounded waiting with injected time.
 *
 * `pollUntil` takes its clock as a parameter so unit tests never sleep for real.
 * `waitVisible` and `waitHidden` turn a Playwright TimeoutError into `false` and
 * rethrow everything else, so a closed page or a crashed browser is still an
 * exception (leg reason EXCEPTION) rather than a quiet "not visible".
 */

export interface Clock {
  now(): number;
  sleep(ms: number): Promise<void>;
}

export const realClock: Clock = {
  now: () => Date.now(),
  sleep: (ms) => new Promise<void>((resolve) => setTimeout(resolve, ms)),
};

export interface PollOptions {
  readonly timeoutMs: number;
  readonly intervalMs: number;
  readonly clock: Clock;
}

/** Resolves true as soon as `predicate` holds, false once `timeoutMs` has elapsed. Never retries an action. */
export async function pollUntil(
  predicate: () => boolean | Promise<boolean>,
  options: PollOptions,
): Promise<boolean> {
  const deadline = options.clock.now() + options.timeoutMs;
  for (;;) {
    if (await predicate()) return true;
    const remaining = deadline - options.clock.now();
    if (remaining <= 0) return false;
    await options.clock.sleep(Math.min(options.intervalMs, remaining));
  }
}

/** Playwright's TimeoutError, recognised by name only (its message can quote page content). */
export function isTimeoutError(error: unknown): boolean {
  return error instanceof Error && error.name === "TimeoutError";
}

export interface LocatorLike {
  waitFor(options: { state: "visible" | "hidden"; timeout: number }): Promise<void>;
}

async function waitForState(locator: LocatorLike, state: "visible" | "hidden", timeoutMs: number): Promise<boolean> {
  try {
    await locator.waitFor({ state, timeout: timeoutMs });
    return true;
  } catch (error) {
    if (isTimeoutError(error)) return false;
    throw error;
  }
}

export function waitVisible(locator: LocatorLike, timeoutMs: number): Promise<boolean> {
  return waitForState(locator, "visible", timeoutMs);
}

export function waitHidden(locator: LocatorLike, timeoutMs: number): Promise<boolean> {
  return waitForState(locator, "hidden", timeoutMs);
}
