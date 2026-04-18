-- V6: Add index on portfolios.user_id to avoid sequential scans in the analytics CTE filter.
CREATE INDEX IF NOT EXISTS idx_portfolios_user_id ON portfolios (user_id);
