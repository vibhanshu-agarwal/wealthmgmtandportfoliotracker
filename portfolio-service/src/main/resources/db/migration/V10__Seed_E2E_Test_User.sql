-- =============================================================================
-- V10: Seed the dedicated E2E test user into Better Auth tables.
--
-- Spec: docs/specs/golden-state-seeder-backend/design.md (Requirement 8)
--
-- Inserts the E2E user (e2e-test-user@vibhanshu-ai-portfolio.dev) into ba_user
-- plus a credential account in ba_account. The Golden-State seeder
-- (/api/internal/portfolio/seed) wipes and re-seeds this user's portfolio data
-- on demand from Playwright global-setup.ts.
--
-- Password hash generated via frontend/scripts/generate-scrypt-hash.ts using
-- Better Auth's exact scrypt parameters (N=16384, r=16, p=1, dkLen=64).
-- Format: {salt_hex}:{derived_key_hex}
--   - salt:  32 hex chars (16 bytes)
--   - key:  128 hex chars (64 bytes)
-- Password (plaintext, for CI env var): "e2e-test-password-2026".
--
-- Uses ON CONFLICT DO NOTHING for idempotency — safe to re-run.
-- =============================================================================

-- 1. Legacy users row. portfolios.user_id is not FK-constrained (V1), but
--    PortfolioService.requireUserExists() queries this table at the application
--    layer, so every authenticated API call (GET /api/portfolio/summary, etc.)
--    needs the E2E user to exist here too — not just in ba_user.
INSERT INTO users (id, email, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000e2e',
    'e2e-test-user@vibhanshu-ai-portfolio.dev',
    NOW()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO "ba_user" ("id", "name", "email", "emailVerified", "image", "createdAt", "updatedAt")
VALUES (
    '00000000-0000-0000-0000-000000000e2e',
    'E2E Test User',
    'e2e-test-user@vibhanshu-ai-portfolio.dev',
    TRUE,
    NULL,
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;

INSERT INTO "ba_account" (
    "id", "accountId", "providerId", "userId",
    "accessToken", "refreshToken", "idToken",
    "accessTokenExpiresAt", "refreshTokenExpiresAt",
    "scope", "password", "createdAt", "updatedAt"
)
VALUES (
    '00000000-0000-0000-0000-000000000e2f',
    '00000000-0000-0000-0000-000000000e2e',
    'credential',
    '00000000-0000-0000-0000-000000000e2e',
    NULL, NULL, NULL,
    NULL, NULL,
    NULL,
    '93a6b29a4c6a6a540521e0f27168ea8d:f373d82152a39a153ce5daac1b12bab403d1e029605bab0f9771a04ac1e0499205547e38b2182a65a8a40d4eb320a2c38e4968592523212f6a260801060505b8',
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;
