import { auth } from "@/lib/auth";
import { mintToken } from "@/lib/auth/mintToken";
import { headers } from "next/headers";
import { NextResponse } from "next/server";

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

  const token = await mintToken(session.user);

  return NextResponse.json({
    token,
    userId: session.user.id,
    email: session.user.email,
  });
}
