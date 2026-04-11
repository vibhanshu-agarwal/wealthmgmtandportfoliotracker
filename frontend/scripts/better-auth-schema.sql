-- =============================================================================
-- Better Auth schema for PostgreSQL
-- Tables use the ba_ prefix to avoid conflicts with Flyway-managed tables
-- (users, portfolios, asset_holdings, market_prices).
--
-- All statements are idempotent (CREATE TABLE IF NOT EXISTS).
-- =============================================================================

-- 1. ba_user — core user accounts
CREATE TABLE IF NOT EXISTS "ba_user" (
    "id"            TEXT PRIMARY KEY NOT NULL,
    "name"          TEXT NOT NULL,
    "email"         TEXT NOT NULL UNIQUE,
    "emailVerified" BOOLEAN NOT NULL DEFAULT FALSE,
    "image"         TEXT,
    "createdAt"     TIMESTAMP NOT NULL DEFAULT NOW(),
    "updatedAt"     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. ba_session — active sessions
CREATE TABLE IF NOT EXISTS "ba_session" (
    "id"        TEXT PRIMARY KEY NOT NULL,
    "expiresAt" TIMESTAMP NOT NULL,
    "token"     TEXT NOT NULL UNIQUE,
    "createdAt" TIMESTAMP NOT NULL DEFAULT NOW(),
    "updatedAt" TIMESTAMP NOT NULL DEFAULT NOW(),
    "ipAddress" TEXT,
    "userAgent" TEXT,
    "userId"    TEXT NOT NULL REFERENCES "ba_user"("id") ON DELETE CASCADE
);

-- 3. ba_account — auth provider accounts (credential, OAuth, etc.)
CREATE TABLE IF NOT EXISTS "ba_account" (
    "id"                    TEXT PRIMARY KEY NOT NULL,
    "accountId"             TEXT NOT NULL,
    "providerId"            TEXT NOT NULL,
    "userId"                TEXT NOT NULL REFERENCES "ba_user"("id") ON DELETE CASCADE,
    "accessToken"           TEXT,
    "refreshToken"          TEXT,
    "idToken"               TEXT,
    "accessTokenExpiresAt"  TIMESTAMP,
    "refreshTokenExpiresAt" TIMESTAMP,
    "scope"                 TEXT,
    "password"              TEXT,
    "createdAt"             TIMESTAMP NOT NULL DEFAULT NOW(),
    "updatedAt"             TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 4. ba_verification — email verification tokens
CREATE TABLE IF NOT EXISTS "ba_verification" (
    "id"         TEXT PRIMARY KEY NOT NULL,
    "identifier" TEXT NOT NULL,
    "value"      TEXT NOT NULL,
    "expiresAt"  TIMESTAMP NOT NULL,
    "createdAt"  TIMESTAMP NOT NULL DEFAULT NOW(),
    "updatedAt"  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes that Better Auth expects
CREATE INDEX IF NOT EXISTS "ba_session_userId_idx" ON "ba_session"("userId");
CREATE INDEX IF NOT EXISTS "ba_account_userId_idx" ON "ba_account"("userId");
CREATE INDEX IF NOT EXISTS "ba_verification_identifier_idx" ON "ba_verification"("identifier");
