-- =============================================================================
-- V9: Seed a local development user into Better Auth tables.
--
-- Source: frontend/scripts/seed-dev-user.ts (TypeScript seed script)
-- Bugfix spec: .kiro/specs/better-auth-signin-fix/
--
-- Inserts a dev user (dev@localhost.local / password) into ba_user and a
-- credential account into ba_account with a pre-computed scrypt password hash.
--
-- The password hash was generated using Better Auth's exact scrypt parameters:
--   N=16384, r=16, p=1, dkLen=64
-- Format: {salt_hex}:{derived_key_hex}
--   - salt:  32 hex chars (16 bytes)
--   - key:  128 hex chars (64 bytes)
--
-- Uses ON CONFLICT DO NOTHING for idempotency — safe to re-run on databases
-- where the dev user already exists.
-- =============================================================================

-- 1. Dev user record
INSERT INTO "ba_user" ("id", "name", "email", "emailVerified", "image", "createdAt", "updatedAt")
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Dev User',
    'dev@localhost.local',
    TRUE,
    NULL,
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;

-- 2. Credential account linked to the dev user
--    Password "password" hashed with scrypt (N=16384, r=16, p=1, dkLen=64)
INSERT INTO "ba_account" (
    "id", "accountId", "providerId", "userId",
    "accessToken", "refreshToken", "idToken",
    "accessTokenExpiresAt", "refreshTokenExpiresAt",
    "scope", "password", "createdAt", "updatedAt"
)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'credential',
    '00000000-0000-0000-0000-000000000001',
    NULL, NULL, NULL,
    NULL, NULL,
    NULL,
    'a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8:e71aef041938b4f1b2d19682f89071cd3ad3bd9ebf0ce4478198843d3d8daba87f9e9aa0544efe1ee4102391fbdb88bc53abfc1e34aedb48f90678b167111973',
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;
