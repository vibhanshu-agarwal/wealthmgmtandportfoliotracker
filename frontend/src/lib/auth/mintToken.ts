import "server-only";

import { SignJWT } from "jose";

/** The session shape expected by mintToken — matches Better Auth's session.user. */
export interface TokenUser {
  id: string;
  email: string;
  name: string;
}

const MIN_HS256_SECRET_LENGTH = 32;

export function getJwtSigningSecret(): Uint8Array {
  const rawSecret = process.env.AUTH_JWT_SECRET ?? process.env.BETTER_AUTH_SECRET;
  const secret = rawSecret?.trim() ?? "";

  if (!secret) {
    throw new Error(
      "JWT signing secret is missing. Set AUTH_JWT_SECRET (or BETTER_AUTH_SECRET fallback).",
    );
  }

  if (secret.length < MIN_HS256_SECRET_LENGTH) {
    throw new Error(
      `JWT signing secret must be at least ${MIN_HS256_SECRET_LENGTH} characters.`,
    );
  }

  return new TextEncoder().encode(secret);
}

/**
 * Mints an HS256 JWT from a Better Auth session user.
 *
 * - Reads AUTH_JWT_SECRET (fallback: BETTER_AUTH_SECRET) at invocation time,
 *   not at module load time, so env changes in tests are picked up.
 * - Encodes the secret as UTF-8 bytes for jose's HS256 signer.
 * - Sets sub, email, name claims from the session user.
 * - Sets iat to current time, exp to iat + 1 hour.
 * - Sets protected header alg to HS256.
 *
 * @param user - The authenticated user from Better Auth session
 * @returns A signed HS256 JWT string
 */
export async function mintToken(user: TokenUser): Promise<string> {
  const secret = getJwtSigningSecret();

  return new SignJWT({
    sub: user.id,
    email: user.email,
    name: user.name,
  })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(secret);
}
