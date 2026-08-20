# Redesign the mocked-chaos 429 test to assert batch behavior, not phantom retries

**Status:** Open
**Priority:** Low
**Date:** 2026-08-20

---

## Background

`frontend/tests/e2e/mocked-chaos.spec.ts`'s "429 Too Many Requests handles
exponential backoff and limits retries" test was quarantined (`test.skip`) in
the `mocked-chaos-assertion-and-sanitizer-font-gap` bugfix (Track B). Its
assertion never measured what its name claims:

- `defaultQueryRetry` (`frontend/src/components/layout/QueryProvider.tsx`)
  returns `false` for any `RateLimitError` / 4xx **before** the retry-count
  check, so a 429 is never retried in this codebase.
- `loadMarketPrices` (`frontend/src/lib/api/portfolio.ts`, `MARKET_PRICE_BATCH_SIZE = 25`)
  fires ticker batches concurrently through `Promise.allSettled`, which absorbs
  every rejection, so `fetchPortfolio` never throws and `defaultQueryRetry` is
  never consulted on this path at all.
- `requestCount` therefore counts **batches** (`ceil(uniqueTickers / 25)`), not
  retries. It passed at `<= 3` only because the pre-Wave-0 dev identity held 2
  tickers (1 batch); B1 Wave 0 switched to the Golden-State identity (159 active
  tickers → 7 batches) and the assertion began failing deterministically at 7.

The mismatch predates Wave 0. Retry policy is already covered directly by
`QueryProvider.test.ts`; batch cardinality and partial-failure by
`portfolio.batching.test.ts`. So the quarantine loses no real coverage — but the
end-to-end error-boundary behavior this test *meant* to check (does the UI
survive N concurrent 429s?) is now untested.

## What a proper replacement should do

Use a **controlled, fixed-holdings portfolio fixture** rather than the mutable
live Golden-State portfolio, so the assertion is not coupled to catalog size,
and assert what actually happens on this path:

1. The expected disjoint ticker batches occur exactly once each (no duplicate
   batch URL / ticker set).
2. No additional batch request appears after the observation window (proving no
   phantom retry loop, positively rather than by a coincidental count).
3. The page degrades gracefully with unavailable prices — navigation and layout
   remain visible, no white screen.

## Scope

Test-only. No production code change is implied — `loadMarketPrices`,
`MARKET_PRICE_BATCH_SIZE`, and `defaultQueryRetry` are all behaving correctly;
only the test's premise was wrong.
