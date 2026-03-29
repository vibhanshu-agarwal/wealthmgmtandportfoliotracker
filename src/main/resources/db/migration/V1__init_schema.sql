-- =============================================================================
-- V1: Initial Schema
-- =============================================================================
-- Cross-module FK relationships are PROHIBITED (Modulith mandate).
-- References across bounded contexts are stored as plain VARCHAR/UUID columns
-- with NO database-level foreign key constraint.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Module: user
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users
(
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL    DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Module: portfolio
-- -----------------------------------------------------------------------------

-- user_id is a plain VARCHAR — deliberately NO FK to the users table.
-- Cross-module FK constraints are prohibited by the Modulith architecture mandate.
CREATE TABLE IF NOT EXISTS portfolios
(
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL    DEFAULT now()
);

-- portfolio_id FK is within the portfolio module — permitted.
CREATE TABLE IF NOT EXISTS asset_holdings
(
    id           UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID           NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    asset_ticker VARCHAR(20)    NOT NULL,
    quantity     NUMERIC(19, 8) NOT NULL    DEFAULT 0,
    UNIQUE (portfolio_id, asset_ticker)
);

-- -----------------------------------------------------------------------------
-- Module: market
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS market_prices
(
    ticker        VARCHAR(20)    PRIMARY KEY,
    current_price NUMERIC(19, 4) NOT NULL,
    updated_at    TIMESTAMP      NOT NULL DEFAULT now()
);
