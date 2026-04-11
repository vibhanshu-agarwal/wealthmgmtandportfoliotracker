-- Fix portfolio user_id to use the UUID seeded in V4 (dev@local user).
-- V3 seeded portfolios with user_id = 'user-001' (a plain string), but
-- PortfolioService.requireUserExists() parses user_id as a UUID and looks it
-- up in the users table. The V4 user has id = '00000000-0000-0000-0000-000000000001'.
UPDATE portfolios
SET user_id = '00000000-0000-0000-0000-000000000001'
WHERE user_id = 'user-001';
