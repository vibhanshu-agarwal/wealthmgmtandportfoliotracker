-- =============================================================================
-- V11: Add cost-basis columns to asset_holdings (additive, nullable, idempotent)
--
-- These columns support unrealized P&L computation from a real average cost basis
-- rather than always defaulting to the current price (which always yields 0 P&L).
--
-- Schema:
--   avg_cost_basis        NUMERIC(19,4) — the average cost per unit in cost_basis_currency
--   cost_basis_currency   VARCHAR(10)   — ISO currency code for the cost basis
--   cost_basis_source     VARCHAR(32)   — how the basis was set: 'SEED', 'ADD_TIME', etc.
--   cost_basis_as_of      TIMESTAMP     — when the cost basis was captured
--
-- All columns are nullable so existing holdings without a basis continue to work;
-- the analytics service treats NULL cost basis as "unavailable" (not $0).
-- =============================================================================

ALTER TABLE asset_holdings
    ADD COLUMN IF NOT EXISTS avg_cost_basis      NUMERIC(19, 4) NULL,
    ADD COLUMN IF NOT EXISTS cost_basis_currency VARCHAR(10)    NULL,
    ADD COLUMN IF NOT EXISTS cost_basis_source   VARCHAR(32)    NULL,
    ADD COLUMN IF NOT EXISTS cost_basis_as_of    TIMESTAMP      NULL;
