import type { Page } from "@playwright/test";

/**
 * Keep a deliberately frozen observation intact until it is captured, then let
 * timers run while the real response and its asynchronous UI work settle.
 * A completed runFor interval cannot flush notifications queued afterward.
 */
export async function resumeAfterObservation<T>(
  clock: Pick<Page["clock"], "resume">,
  observation: Promise<T>,
): Promise<T> {
  const observed = await observation;
  await clock.resume();
  return observed;
}
