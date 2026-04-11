/**
 * Seed a local development user into Better Auth's ba_user and ba_account tables.
 *
 * Uses Better Auth's signUpEmail API so the password is hashed with scrypt
 * automatically — no manual hash generation needed.
 *
 * Usage:
 *   npx tsx frontend/scripts/seed-dev-user.ts
 *
 * Prerequisites:
 *   - PostgreSQL running with the ba_* tables created (see better-auth-schema.sql)
 *   - DATABASE_URL and BETTER_AUTH_SECRET set in the environment (or .env.local)
 */

import { auth } from "../src/lib/auth";

const DEV_USER = {
  id: "00000000-0000-0000-0000-000000000001",
  email: "dev@localhost.local",
  name: "Dev User",
  password: "password",
};

async function seed() {
  console.log("Seeding dev user…");

  // Check if the user already exists (idempotent)
  const existing = await auth.api.getSession({
    headers: new Headers(),
  }).catch(() => null);

  // Try to sign up — if the email already exists Better Auth returns an error,
  // which we treat as "already seeded".
  const result = await auth.api.signUpEmail({
    body: {
      email: DEV_USER.email,
      password: DEV_USER.password,
      name: DEV_USER.name,
    },
  }).catch((err: unknown) => {
    const message = err instanceof Error ? err.message : String(err);
    // "User already exists" is the expected error on re-runs
    if (message.toLowerCase().includes("already exist")) {
      console.log("Dev user already exists — skipping.");
      return null;
    }
    throw err;
  });

  if (result) {
    console.log(`Dev user created: ${result.user.email} (${result.user.id})`);
  }

  process.exit(0);
}

seed().catch((err) => {
  console.error("Seed failed:", err);
  process.exit(1);
});
