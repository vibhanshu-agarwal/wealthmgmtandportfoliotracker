-- =============================================================================
-- V13: Add unique constraint on market_price_history(ticker, observed_at)
--
-- Enforces idempotent forward-append semantics: a second write for the same
-- (ticker, observed_at) pair is a no-op (ON CONFLICT DO NOTHING), while a new
-- observed_at with the same price is correctly treated as a distinct observation.
--
-- The observed_at column is typed as TIMESTAMP (microsecond precision in Postgres).
-- Call sites must normalize to millisecond precision before using (ticker, observed_at)
-- as an identity key to avoid false duplicates from sub-millisecond drift.
--
-- This migration is additive and idempotent (CREATE UNIQUE INDEX IF NOT EXISTS).
-- The index added by V2 (idx_market_price_history_ticker_observed_at) is a plain
-- non-unique index and is deliberately left in place; the new unique constraint
-- is a separate index, consistent with the "additive" rule for migrations.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS
    uidx_market_price_history_ticker_observed_at
    ON market_price_history (ticker, observed_at);
