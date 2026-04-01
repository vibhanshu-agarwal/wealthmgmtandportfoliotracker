-- =============================================================================
-- V3: Seed demo portfolio and holdings for UI/backend integration
-- =============================================================================

INSERT INTO users (email)
VALUES ('demo@wealth.local')
ON CONFLICT (email) DO NOTHING;

WITH selected_portfolio AS (
    SELECT id
    FROM portfolios
    WHERE user_id = 'user-001'
    ORDER BY created_at
    LIMIT 1
),
created_portfolio AS (
    INSERT INTO portfolios (user_id)
    SELECT 'user-001'
    WHERE NOT EXISTS (SELECT 1 FROM selected_portfolio)
    RETURNING id
),
portfolio_ref AS (
    SELECT id FROM selected_portfolio
    UNION ALL
    SELECT id FROM created_portfolio
)
INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
SELECT id, 'AAPL', 12.00000000 FROM portfolio_ref
ON CONFLICT (portfolio_id, asset_ticker) DO UPDATE SET quantity = EXCLUDED.quantity;

WITH selected_portfolio AS (
    SELECT id
    FROM portfolios
    WHERE user_id = 'user-001'
    ORDER BY created_at
    LIMIT 1
)
INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
SELECT id, 'TSLA', 8.00000000 FROM selected_portfolio
ON CONFLICT (portfolio_id, asset_ticker) DO UPDATE SET quantity = EXCLUDED.quantity;

WITH selected_portfolio AS (
    SELECT id
    FROM portfolios
    WHERE user_id = 'user-001'
    ORDER BY created_at
    LIMIT 1
)
INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
SELECT id, 'BTC', 0.75000000 FROM selected_portfolio
ON CONFLICT (portfolio_id, asset_ticker) DO UPDATE SET quantity = EXCLUDED.quantity;
