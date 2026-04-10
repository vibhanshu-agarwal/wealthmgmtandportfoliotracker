import NextAuth from "next-auth";
import { SignJWT, jwtVerify } from "jose";
import type { JWT } from "next-auth/jwt";
import { authConfig } from "./auth.config";

// Derive the signing key from AUTH_JWT_SECRET — the same env var used by the API Gateway.
// This ensures the JWS produced here is accepted by NimbusReactiveJwtDecoder.withSecretKey().
const secret = new TextEncoder().encode(process.env.AUTH_JWT_SECRET);

export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,

  session: {
    strategy: "jwt",
  },

  callbacks: {
    // Populate the JWT payload with the user's id as the `sub` claim.
    // Runs on sign-in and on every session access.
    async jwt({ token, user }) {
      if (user?.id) {
        token.sub = user.id;
      }
      // Ensure __rawJwt is always populated so session() can surface it as accessToken.
      // When a raw JWT cookie is injected directly (e.g. Playwright E2E auth helper),
      // encode() never runs, so __rawJwt is not stashed. Re-sign here as a fallback.
      if (!token.__rawJwt) {
        const payload = { ...(token as Record<string, unknown>) };
        delete payload.__rawJwt;
        const reissued = await new SignJWT(payload)
          .setProtectedHeader({ alg: "HS256" })
          .setIssuedAt()
          .setExpirationTime("1h")
          .sign(secret);
        token.__rawJwt = reissued;
      }
      return token;
    },

    // Expose the authenticated user id and raw JWT string in the session object.
    // `accessToken` carries the signed JWS string for use in Authorization headers.
    async session({ session, token }) {
      session.user.id = token.sub ?? "";
      // Surface the raw JWS string stashed by encode() so client code can attach it
      // as a Bearer token without a second signing operation.
      (session as { accessToken?: string }).accessToken =
        token.__rawJwt as string | undefined;
      return session;
    },
  },

  jwt: {
    // Override NextAuth's default JWE encryption.
    // Produce a plain HS256-signed JWS that the Spring API Gateway can verify directly
    // using NimbusReactiveJwtDecoder.withSecretKey(AUTH_JWT_SECRET).
    async encode({ token }): Promise<string> {
      const payload = { ...(token as Record<string, unknown>) };
      const jwt = await new SignJWT(payload)
        .setProtectedHeader({ alg: "HS256" })
        .setIssuedAt()
        .setExpirationTime("1h")
        .sign(secret);

      // Stash the raw JWS string back onto the token so the session() callback
      // can read it without a second SignJWT call (__rawJwt stash pattern).
      if (token) {
        (token as Record<string, unknown>).__rawJwt = jwt;
      }

      return jwt;
    },

    async decode({ token }): Promise<JWT | null> {
      if (!token) return null;
      try {
        const { payload } = await jwtVerify(token, secret, {
          algorithms: ["HS256"],
        });
        return payload as JWT;
      } catch {
        return null;
      }
    },
  },
});
