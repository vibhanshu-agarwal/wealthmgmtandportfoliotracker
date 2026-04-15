import { auth } from "@/lib/auth";
import { getJwtSigningSecret, mintToken } from "@/lib/auth/mintToken";
import { jwtVerify } from "jose";
import { headers } from "next/headers";
import { NextResponse } from "next/server";

/**
 * Auth JWT health endpoint for operational checks.
 *
 * Performs an authenticated self-check:
 * 1) validates session lookup
 * 2) mints a JWT for current session user
 * 3) verifies signature/claims with active secret
 */
export async function GET() {
  try {
    const reqHeaders = await headers();

    let session: Awaited<ReturnType<typeof auth.api.getSession>>;
    try {
      session = await auth.api.getSession({ headers: reqHeaders });
    } catch (error) {
      console.error("Session lookup failed in /api/auth/jwt/health", error);
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
      const secret = getJwtSigningSecret();
      const { payload } = await jwtVerify(token, secret, { algorithms: ["HS256"] });

      if (payload.sub !== session.user.id) {
        throw new Error("JWT subject mismatch during health check");
      }

      return NextResponse.json({
        status: "ok",
        checks: {
          sessionLookup: true,
          tokenMint: true,
          tokenVerify: true,
        },
        userId: session.user.id,
      });
    } catch (error) {
      console.error("JWT self-check failed in /api/auth/jwt/health", error);
      return NextResponse.json(
        { error: "Token service unavailable", retryable: true },
        { status: 503 },
      );
    }
  } catch (error) {
    console.error("Unexpected failure in /api/auth/jwt/health", error);
    return NextResponse.json({ error: "Internal server error" }, { status: 500 });
  }
}
