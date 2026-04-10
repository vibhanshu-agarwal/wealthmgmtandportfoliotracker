"use client";

import { SessionProvider as NextAuthSessionProvider } from "next-auth/react";
import type { Session } from "next-auth";
import React from "react";

interface SessionProviderProps {
  children: React.ReactNode;
  // Pass the server-fetched session so useSession() returns "authenticated"
  // immediately on first render — no client-side /api/auth/session round-trip.
  session?: Session | null;
}

export function SessionProvider({ children, session }: SessionProviderProps) {
  return (
    <NextAuthSessionProvider session={session}>
      {children}
    </NextAuthSessionProvider>
  );
}
