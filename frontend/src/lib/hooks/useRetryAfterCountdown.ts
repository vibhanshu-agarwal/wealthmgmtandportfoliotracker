"use client";

import { useEffect, useRef, useState, useCallback } from "react";

/**
 * Drives a one-second-tick countdown from an initial number of seconds down to zero.
 *
 * Used to disable a submit control with a visible countdown after a 429 response
 * (Req 6.6): `start(seconds)` begins the countdown, `secondsRemaining` is the live
 * value to render, and `isActive` tells the caller whether the control should stay
 * disabled. When `seconds` is null or not a positive integer, `start` is a no-op —
 * callers should fall back to a fixed disable duration (or none) rather than
 * showing a countdown for an unparseable `Retry-After` value.
 */
export function useRetryAfterCountdown() {
  const [secondsRemaining, setSecondsRemaining] = useState(0);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const clear = useCallback(() => {
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const start = useCallback(
    (seconds: number | null) => {
      clear();
      if (seconds === null || !Number.isInteger(seconds) || seconds <= 0) {
        setSecondsRemaining(0);
        return;
      }
      setSecondsRemaining(seconds);
      intervalRef.current = setInterval(() => {
        setSecondsRemaining((prev) => {
          if (prev <= 1) {
            clear();
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
    },
    [clear],
  );

  useEffect(() => clear, [clear]);

  return {
    secondsRemaining,
    isActive: secondsRemaining > 0,
    start,
  };
}
