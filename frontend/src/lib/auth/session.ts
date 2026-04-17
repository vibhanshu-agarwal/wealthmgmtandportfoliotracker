"use client";

import { useEffect, useState } from "react";
import { apiPath } from "@/lib/config/api";

const AUTH_STORAGE_KEY = "wmpt.auth.session";

export interface AuthSession {
  token: string;
  userId: string;
  email: string;
  name: string;
}

interface LoginResponse {
  token: string;
  userId: string;
  email: string;
  name: string;
}

function parseStoredSession(raw: string | null): AuthSession | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<AuthSession>;
    if (!parsed.token || !parsed.userId || !parsed.email || !parsed.name) {
      return null;
    }
    return {
      token: parsed.token,
      userId: parsed.userId,
      email: parsed.email,
      name: parsed.name,
    };
  } catch {
    return null;
  }
}

export function loadAuthSession(): AuthSession | null {
  if (typeof window === "undefined") return null;
  return parseStoredSession(window.localStorage.getItem(AUTH_STORAGE_KEY));
}

export function saveAuthSession(session: AuthSession): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
}

export function clearAuthSession(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(AUTH_STORAGE_KEY);
}

export async function loginWithBackend(email: string, password: string): Promise<AuthSession> {
  const response = await fetch(apiPath("/auth/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });

  if (!response.ok) {
    throw new Error(`Login failed (${response.status})`);
  }

  const payload = (await response.json()) as LoginResponse;
  const session: AuthSession = {
    token: payload.token,
    userId: payload.userId,
    email: payload.email,
    name: payload.name,
  };
  saveAuthSession(session);
  return session;
}

export function useAuthSession() {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [isPending, setIsPending] = useState(true);

  useEffect(() => {
    setSession(loadAuthSession());
    setIsPending(false);
  }, []);

  return {
    data: session,
    isPending,
    isAuthenticated: !!session,
    setSession,
  };
}
