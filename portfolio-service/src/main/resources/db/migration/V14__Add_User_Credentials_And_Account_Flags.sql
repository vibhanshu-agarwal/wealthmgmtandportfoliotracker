-- =============================================================================
-- V14: Add user_credentials (per-user login credentials) and account flags on
-- users (name, read_only). Owned by portfolio-service (the Schema_Owner);
-- api-gateway reads/writes these tables but defines no migrations of its own.
-- =============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS name      VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS user_credentials
(
    user_id       UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Case-insensitive email uniqueness without requiring the citext extension.
-- This is the concurrency guard for duplicate signups (Req 1.9, 2.8): two
-- concurrent INSERTs for the same email (any case) have exactly one winner.
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_credentials_email_lower
    ON user_credentials (lower(email));
