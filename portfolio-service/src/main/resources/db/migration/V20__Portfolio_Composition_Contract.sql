-- =============================================================================
-- V20: portfolio composition contract foundation.
--
-- Ordering is load-bearing: backfill must complete before the unique constraint,
-- and the quantity default must be removed before its positive-value constraint.
-- =============================================================================

ALTER TABLE portfolios
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE portfolios
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT now();

INSERT INTO portfolios (id, user_id, created_at)
SELECT gen_random_uuid(), u.id::text, now()
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM portfolios p
    WHERE p.user_id = u.id::text
);

ALTER TABLE portfolios
    ADD CONSTRAINT uq_portfolios_user_id UNIQUE (user_id);

ALTER TABLE asset_holdings
    ALTER COLUMN quantity DROP DEFAULT;

ALTER TABLE asset_holdings
    ADD CONSTRAINT chk_asset_holdings_quantity_positive CHECK (quantity > 0);
