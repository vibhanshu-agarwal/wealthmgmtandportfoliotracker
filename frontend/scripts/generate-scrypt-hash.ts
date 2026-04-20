/**
 * Generate a Better-Auth-compatible scrypt password hash.
 *
 * The produced string is consumed verbatim by
 * `portfolio-service/src/main/resources/db/migration/V10__Seed_E2E_Test_User.sql`
 * (and V9 for the dev user). Parameters match Better Auth's credential plugin:
 *   N=16384, r=16, p=1, dkLen=64
 *
 * Output format:
 *   <saltHex>:<derivedKeyHex>
 *     - salt:  32 hex chars (16 random bytes)
 *     - key:  128 hex chars (64 bytes)
 *
 * Usage:
 *   npx tsx frontend/scripts/generate-scrypt-hash.ts "mySecurePassword"
 *   # copy the output line into the "password" column of V10__Seed_E2E_Test_User.sql
 *
 * The script is a developer utility: it is not invoked by the build or by CI.
 * Run it ad-hoc whenever the E2E test user's password needs rotation, then commit
 * the updated V10 migration.
 */

import { randomBytes, scryptSync } from "node:crypto";

const N = 16384;
const r = 16;
const p = 1;
const dkLen = 64;
const saltBytes = 16;

function main(): void {
  const password = process.argv[2];
  if (!password) {
    console.error("usage: tsx frontend/scripts/generate-scrypt-hash.ts <password>");
    process.exit(1);
  }

  const salt = randomBytes(saltBytes);
  // Node's scryptSync defaults maxmem to 32 MiB which is below the (128 * N * r)
  // working-set required for N=16384/r=16 (~32 MiB). Raise the ceiling explicitly.
  const key = scryptSync(password, salt, dkLen, { N, r, p, maxmem: 128 * 1024 * 1024 });

  process.stdout.write(`${salt.toString("hex")}:${key.toString("hex")}\n`);
}

main();
