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
  try {
    const reqHeaders = await headers();

    let session: Awaited<ReturnType<typeof auth.api.getSession>>;
    try {
      session = await auth.api.getSession({ headers: reqHeaders });
    } catch (error) {
      console.error("Session lookup failed in /api/auth/jwt", error);
      return NextResponse.json(
        { error: "Authentication service unavailable", retryable: true },
        { status: 503 },
      );
    }

    if (!session?.user?.id) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    try {
      const token = await mintToken(session.user);
      return NextResponse.json({
        token,
        userId: session.user.id,
        email: session.user.email,
      });
    } catch (error) {
      console.error("Token minting failed in /api/auth/jwt", error);
      const message = error instanceof Error ? error.message : "";
      const isConfigError =
        message.includes("JWT signing secret") || message.includes("AUTH_JWT_SECRET");

      if (isConfigError) {
        return NextResponse.json(
          { error: "Token service unavailable", retryable: true },
          { status: 503 },
        );
      }

      return NextResponse.json(
        { error: "Internal token exchange failure" },
        { status: 500 },
      );
    }
  } catch (error) {
    console.error("Unexpected failure in /api/auth/jwt", error);
    return NextResponse.json({ error: "Internal server error" }, { status: 500 });
  }
}
