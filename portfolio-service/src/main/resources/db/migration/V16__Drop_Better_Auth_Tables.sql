-- =============================================================================
-- V16: Drop the retired Better Auth tables. Versioned as the HIGHEST number in
-- this release so Flyway applies it last — ships in the same release as the
-- frontend Better Auth code removal (Task 7); no deployed build may reference
-- ba_* after this runs.
-- =============================================================================

DROP TABLE IF EXISTS ba_verification;
DROP TABLE IF EXISTS ba_account;
DROP TABLE IF EXISTS ba_session;
DROP TABLE IF EXISTS ba_user;
