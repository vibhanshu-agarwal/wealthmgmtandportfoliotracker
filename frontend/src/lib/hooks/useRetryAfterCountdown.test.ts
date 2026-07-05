import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { useRetryAfterCountdown } from "./useRetryAfterCountdown";

describe("useRetryAfterCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts inactive with zero seconds remaining", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    expect(result.current.isActive).toBe(false);
    expect(result.current.secondsRemaining).toBe(0);
  });

  it("counts down from the given seconds to zero, one tick per second", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(3));
    expect(result.current.secondsRemaining).toBe(3);
    expect(result.current.isActive).toBe(true);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.secondsRemaining).toBe(2);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.secondsRemaining).toBe(1);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.secondsRemaining).toBe(0);
    expect(result.current.isActive).toBe(false);
  });

  it("does not go negative once it reaches zero", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(1));
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.secondsRemaining).toBe(0);

    act(() => vi.advanceTimersByTime(5000));
    expect(result.current.secondsRemaining).toBe(0);
  });

  it("is a no-op (stays inactive) when started with null", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(null));

    expect(result.current.isActive).toBe(false);
    expect(result.current.secondsRemaining).toBe(0);
  });

  it("is a no-op when started with zero or a negative value", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(0));
    expect(result.current.isActive).toBe(false);

    act(() => result.current.start(-5));
    expect(result.current.isActive).toBe(false);
  });

  it("restarting cancels the previous interval instead of stacking ticks", () => {
    const { result } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(10));
    act(() => vi.advanceTimersByTime(2000)); // 10 -> 8
    expect(result.current.secondsRemaining).toBe(8);

    act(() => result.current.start(3));
    expect(result.current.secondsRemaining).toBe(3);

    act(() => vi.advanceTimersByTime(1000));
    // If the old interval were still running, this would be 3 - 1 - 1 = 1.
    expect(result.current.secondsRemaining).toBe(2);
  });

  it("clears the interval on unmount without throwing", () => {
    const { result, unmount } = renderHook(() => useRetryAfterCountdown());

    act(() => result.current.start(5));
    expect(() => unmount()).not.toThrow();

    // Advancing timers after unmount must not error or resurrect state.
    expect(() => act(() => vi.advanceTimersByTime(5000))).not.toThrow();
  });
});
