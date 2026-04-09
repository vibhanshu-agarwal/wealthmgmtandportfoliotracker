import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: {
      id: string;
    } & DefaultSession["user"];
    /** Raw HS256-signed JWS string — attach as Authorization: Bearer <accessToken> */
    accessToken?: string;
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    sub?: string;
    /** Stashed by jwt.encode() so session() can surface it without re-signing */
    __rawJwt?: string;
  }
}
