-- Seed a fixed local development user so integration tests and local dev
-- can use a known UUID as the sub_claim in JWTs.
-- Uses ON CONFLICT DO NOTHING to make this migration idempotent.
INSERT INTO users (id, email, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'dev@local',
    NOW()
)
ON CONFLICT DO NOTHING;
