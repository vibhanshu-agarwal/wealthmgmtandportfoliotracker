import type { NextAuthConfig } from "next-auth";
import Credentials from "next-auth/providers/credentials";

export const authConfig: NextAuthConfig = {
  pages: {
    signIn: "/login",
  },

  callbacks: {
    authorized({ auth, request: { nextUrl } }) {
      const isLoggedIn = !!auth?.user;
      const isLoginPage = nextUrl.pathname === "/login";
      const isAuthApi = nextUrl.pathname.startsWith("/api/auth");

      // Always allow NextAuth's own API routes through
      if (isAuthApi) return true;

      // Redirect unauthenticated users to /login
      if (!isLoggedIn && !isLoginPage) {
        return Response.redirect(new URL("/login", nextUrl));
      }

      // Redirect already-authenticated users away from /login
      if (isLoggedIn && isLoginPage) {
        return Response.redirect(new URL("/overview", nextUrl));
      }

      return true;
    },
  },

  providers: [
    Credentials({
      name: "Credentials",
      credentials: {
        username: { label: "Username", type: "text" },
        password: { label: "Password", type: "password" },
      },
      async authorize(credentials) {
        // Local dev: mock a successful login for "user-001".
        // No real DB lookup — keeps local development frictionless.
        // TODO (aws profile): replace with real DB lookup against users table.
        if (
          credentials?.username === "user-001" &&
          credentials?.password === "password"
        ) {
          return { id: "user-001", name: "Dev User", email: "dev@local" };
        }
        return null;
      },
    }),
  ],
};
