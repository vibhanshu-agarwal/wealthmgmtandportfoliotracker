-- =============================================================================
-- V1: Initial schema mirroring current JPA entities
-- =============================================================================

CREATE TABLE IF NOT EXISTS users
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS portfolios
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS asset_holdings
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID           NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    asset_ticker VARCHAR(20)    NOT NULL,
    quantity     NUMERIC(19, 8) NOT NULL DEFAULT 0,
    UNIQUE (portfolio_id, asset_ticker)
);

CREATE TABLE IF NOT EXISTS market_prices
(
    ticker        VARCHAR(20)    PRIMARY KEY,
    current_price NUMERIC(19, 4) NOT NULL,
    updated_at    TIMESTAMP      NOT NULL DEFAULT now()
);
