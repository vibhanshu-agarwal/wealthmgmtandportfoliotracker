// @vitest-environment node
import { QueryClient, QueryObserver, notifyManager } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { resumeAfterObservation } from "../browser-clock";

afterEach(() => vi.useRealTimers());

describe("demo reset browser clock observations", () => {
  it.each([0, 65_000])("delivers real QueryObserver notifications queued after a %i ms frozen window", async (elapsed) => {
    vi.useFakeTimers();
    const queryKey = ["portfolio", "demo-clock-regression"];
    const client = new QueryClient();
    client.setQueryData(queryKey, "non-golden");
    const observer = new QueryObserver<string>(client, { queryKey, enabled: false });
    let visible = observer.getCurrentResult().data;
    // This is the real batching/scheduling boundary useBaseQuery uses for React.
    const unsubscribe = observer.subscribe(notifyManager.batchCalls((result) => { visible = result.data; }));
    try {
      await vi.advanceTimersByTimeAsync(elapsed);
      // A network response can arrive after runFor has ended. Its zero-delay
      // cache notification is not part of the time interval already advanced.
      const observation = Promise.resolve().then(() => {
        client.setQueryData(queryKey, "fresh-golden");
        return { version: 8 };
      });
      await observation;
      expect(visible).toBe("non-golden");
      const response = await resumeAfterObservation({
        resume: async () => { await vi.advanceTimersByTimeAsync(0); },
      }, observation);
      expect(response).toEqual({ version: 8 });
      expect(visible).toBe("fresh-golden");
    } finally {
      unsubscribe();
      client.clear();
    }
  });

  it("keeps the version observation frozen until the request has been captured", async () => {
    const events: string[] = [];
    let capture!: (request: { expectedVersion: number }) => void;
    const request = new Promise<{ expectedVersion: number }>((resolve) => { capture = resolve; });
    const result = resumeAfterObservation({ resume: async () => { events.push("resumed"); } }, request);
    await Promise.resolve();
    expect(events).toEqual([]);
    events.push("captured");
    capture({ expectedVersion: 7 });
    await expect(result).resolves.toEqual({ expectedVersion: 7 });
    expect(events).toEqual(["captured", "resumed"]);
  });

  it("propagates a failed observation without hiding the failure or resuming early", async () => {
    const resume = vi.fn();
    const failure = new Error("response observation failed");
    await expect(resumeAfterObservation({ resume }, Promise.reject(failure))).rejects.toBe(failure);
    expect(resume).not.toHaveBeenCalled();
  });
});
