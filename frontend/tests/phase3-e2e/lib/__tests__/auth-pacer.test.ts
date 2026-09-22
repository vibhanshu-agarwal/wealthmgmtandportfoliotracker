import { mkdtempSync, rmSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { fileStore, paceAuthRequest, type PacerClock } from "../auth-pacer";

function fakeClock(start: number): PacerClock & { slept: number[] } {
  let now = start;
  const slept: number[] = [];
  return {
    slept,
    now: () => now,
    sleep: async (ms: number) => {
      slept.push(ms);
      now += ms;
    },
  };
}

const dirs: string[] = [];
function tempStorePath() {
  const dir = mkdtempSync(path.join(os.tmpdir(), "p3-pacer-"));
  dirs.push(dir);
  return path.join(dir, "auth-pacer.json");
}

afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

describe("paceAuthRequest", () => {
  it("does not wait for the first request", async () => {
    const clock = fakeClock(1_000_000);
    const result = await paceAuthRequest(fileStore(tempStorePath()), 13_000, clock);
    expect(result.waitedMs).toBe(0);
    expect(clock.slept).toEqual([]);
  });

  it("waits out the remainder of the interval since the previous request", async () => {
    const clock = fakeClock(1_000_000);
    const store = fileStore(tempStorePath());
    await paceAuthRequest(store, 13_000, clock);
    await clock.sleep(4_000);
    clock.slept.length = 0;

    const result = await paceAuthRequest(store, 13_000, clock);

    expect(result.waitedMs).toBe(9_000);
    expect(clock.slept).toEqual([9_000]);
  });

  it("does not wait once the interval has already elapsed", async () => {
    const clock = fakeClock(1_000_000);
    const store = fileStore(tempStorePath());
    await paceAuthRequest(store, 13_000, clock);
    await clock.sleep(20_000);
    clock.slept.length = 0;

    expect((await paceAuthRequest(store, 13_000, clock)).waitedMs).toBe(0);
  });

  it("keeps the spacing across a new store instance (a worker restart)", async () => {
    const storePath = tempStorePath();
    const clock = fakeClock(5_000_000);
    await paceAuthRequest(fileStore(storePath), 13_000, clock);
    await clock.sleep(1_000);

    const result = await paceAuthRequest(fileStore(storePath), 13_000, clock);

    expect(result.waitedMs).toBe(12_000);
  });

  it("never waits with a zero interval", async () => {
    const clock = fakeClock(1_000_000);
    const store = fileStore(tempStorePath());
    await paceAuthRequest(store, 0, clock);
    expect((await paceAuthRequest(store, 0, clock)).waitedMs).toBe(0);
  });
});
