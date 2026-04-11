-- =============================================================================
-- Seed a fixed local development user into Better Auth tables.
--
-- IMPORTANT: The TypeScript seed script (seed-dev-user.ts) is preferred because
-- it uses Better Auth's built-in scrypt hashing. This SQL script uses a
-- pre-computed scrypt hash for the password "password" and exists as a fallback.
--
-- Uses ON CONFLICT DO NOTHING for idempotency.
-- =============================================================================

-- Dev user record (matches Flyway V4 seed user ID)
INSERT INTO "ba_user" ("id", "name", "email", "emailVerified", "image", "createdAt", "updatedAt")
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Dev User',
    'dev@localhost.local',
    FALSE,
    NULL,
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;

-- Credential account linked to the dev user.
-- The password field should contain a scrypt hash of "password".
-- Use the TypeScript seed script (seed-dev-user.ts) to generate the correct hash,
-- then paste it here if you need a pure-SQL seed.
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
    -- Placeholder: replace with actual scrypt hash from seed-dev-user.ts output
    '$scrypt$n=16384,r=8,p=1$PLACEHOLDER_HASH',
    NOW(),
    NOW()
)
ON CONFLICT ("id") DO NOTHING;
