import { auth } from "@/lib/auth";
import { SignJWT } from "jose";
import { headers } from "next/headers";
import { NextResponse } from "next/server";

const secret = new TextEncoder().encode(
  process.env.AUTH_JWT_SECRET ?? process.env.BETTER_AUTH_SECRET,
);

/**
 * BFF Token Exchange — mints an HS256 JWT from the authenticated Better Auth session.
 *
 * GET /api/auth/jwt → { "token": "eyJ...", "userId": "...", "email": "..." }
 *
 * Returns the userId alongside the token so the client hook can extract
 * both from a single request — no dependency on useSession().
 */
export async function GET() {
  const reqHeaders = await headers();
  const session = await auth.api.getSession({ headers: reqHeaders });

  if (!session?.user?.id) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const token = await new SignJWT({
    sub: session.user.id,
    email: session.user.email,
    name: session.user.name,
  })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(secret);

  return NextResponse.json({
    token,
    userId: session.user.id,
    email: session.user.email,
  });
}
