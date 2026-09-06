// @vitest-environment node
import type { Page } from "@playwright/test";
import { afterEach, describe, expect, it, vi } from "vitest";
import { assertNoAutomaticPickerRetry } from "../asset-picker-real";

type ObservationClock = Pick<Page["clock"], "pauseAt" | "runFor" | "resume">;

afterEach(() => vi.useRealTimers());

function controlledClock(): ObservationClock {
  return {
    pauseAt: async (time) => {
      if (typeof time !== "number" || time <= Date.now()) {
        throw new Error("The observation must pause at a future browser timestamp");
      }
      await vi.advanceTimersByTimeAsync(time - Date.now());
    },
    runFor: async (duration) => {
      if (typeof duration !== "number") throw new Error("Expected a numeric observation window");
      await vi.advanceTimersByTimeAsync(duration);
    },
    resume: async () => { await vi.advanceTimersByTimeAsync(0); },
  };
}

describe("picker retry observation window", () => {
  it.each([
    [200, 2_000],
    [409, 2_000],
    [200, 30_000],
    [409, 30_000],
  ])("rejects a retry %i response schedules after %i ms", async (_status, delay) => {
    vi.useFakeTimers();
    let requestCount = 1;
    // The second request has no response: its start alone must fail the oracle.
    setTimeout(() => { requestCount += 1; }, delay);

    await expect(assertNoAutomaticPickerRetry(
      controlledClock(), Date.now(), () => requestCount,
    )).rejects.toThrow(/exactly one request, observed 2/);
  });

  it("resumes browser timers before accepting a save with no extra request", async () => {
    vi.useFakeTimers();
    const events: string[] = [];
    const clock = controlledClock();
    clock.resume = async () => { events.push("resumed"); };

    await assertNoAutomaticPickerRetry(clock, Date.now(), () => {
      events.push("counted");
      return 1;
    });

    expect(events).toEqual(["resumed", "counted"]);
  });

  it("resumes browser timers even when advancing the observation fails", async () => {
    vi.useFakeTimers();
    const failure = new Error("clock advancement failed");
    const clock = controlledClock();
    clock.runFor = async () => { throw failure; };
    const resume = vi.fn(async () => {});
    clock.resume = resume;

    await expect(assertNoAutomaticPickerRetry(clock, Date.now(), () => 1)).rejects.toBe(failure);
    expect(resume).toHaveBeenCalledOnce();
  });
});
