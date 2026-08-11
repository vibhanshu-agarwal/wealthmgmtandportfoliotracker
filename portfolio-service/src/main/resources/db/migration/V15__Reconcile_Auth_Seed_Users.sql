-- =============================================================================
-- V15: Idempotently reconcile auth identities for demo/dev/E2E users and
-- reassign the seeded showcase portfolio from the dev user to the new demo
-- account so the read-only recruiter login lands on a populated dashboard.
--
-- Confirmed against the live schema (see plan header): asset_holdings has NO
-- user_id column (only portfolio_id FK, ON DELETE CASCADE) and there is no
-- separate valuation_history table — so reassigning portfolios.user_id alone
-- is sufficient; holdings follow automatically via the FK.
--
-- Password hashes below are bcrypt(cost=12), generated fresh via
-- BCryptPasswordEncoder(12) — NOT reused from any legacy ba_account scrypt
-- hash (a different, incompatible algorithm). The demo account's plaintext
-- password is intentionally public (see the comment above its INSERT below,
-- wired to NEXT_PUBLIC_DEMO_PASSWORD for the login pre-fill) and is the only
-- one of the three recorded in this file. The dev and E2E accounts are real
-- (if low-value) credentials — their plaintext values are tracked out of
-- band (local-dev notes / CI secrets respectively) and are deliberately not
-- restated here.
-- =============================================================================

-- Demo/recruiter account (read-only). Password is >=12 chars and intentionally
-- public (wired to NEXT_PUBLIC_DEMO_PASSWORD for the login pre-fill).
INSERT INTO users (id, email, name, read_only, created_at)
VALUES ('00000000-0000-0000-0000-0000000d3110', 'demo@wealthtracker.dev', 'Demo User', TRUE, now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-0000000d3110', 'demo@wealthtracker.dev', '$2a$12$lD4AMN0qkigtNxeMvULqOu.gBV/83vkFo2iZwRtFMgbdiUN2ibBiu')
ON CONFLICT (user_id) DO NOTHING;

-- Dev user: already exists in `users` (seeded by V4); add name + credentials,
-- read_only stays FALSE (dev user remains writable for local development).
UPDATE users SET name = COALESCE(name, 'Dev User') WHERE id = '00000000-0000-0000-0000-000000000001';
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-000000000001', 'dev@local', '$2a$12$TEqKMNh0VRnziVZBeOPwT.ZR707VQq.WuyqTY5bEp5Kn2itrI4K7O')
ON CONFLICT (user_id) DO NOTHING;

-- E2E test user: already exists in `users` (seeded by V10); add name + fresh
-- bcrypt credentials (NOT the legacy ba_account scrypt hash). read_only stays
-- FALSE so E2E write tests against /api/portfolio/** keep working.
UPDATE users SET name = COALESCE(name, 'E2E Test User') WHERE id = '00000000-0000-0000-0000-000000000e2e';
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-000000000e2e', 'e2e-test-user@vibhanshu-ai-portfolio.dev', '$2a$12$PLjBXBZcBLoxr7la/H7EkeXmPBCmvZjLkAfLYSOJ7vqdb2D6Xnocy')
ON CONFLICT (user_id) DO NOTHING;

-- Reassign the seeded showcase portfolio (V3's AAPL/TSLA/BTC portfolio) from
-- the dev user to the demo account. Guarded on the CURRENT owner so re-running
-- this migration is idempotent: once the row belongs to the demo UUID, the
-- WHERE clause no longer matches and nothing is re-assigned. asset_holdings
-- needs no separate UPDATE — it has no user_id column, only portfolio_id (FK,
-- ON DELETE CASCADE), so ownership follows automatically.
UPDATE portfolios
   SET user_id = '00000000-0000-0000-0000-0000000d3110'
 WHERE user_id = '00000000-0000-0000-0000-000000000001';
