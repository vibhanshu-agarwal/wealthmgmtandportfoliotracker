"use client";

import { useSyncExternalStore } from "react";
import { apiPath } from "@/lib/config/api";

const AUTH_STORAGE_KEY = "wmpt.auth.session";
const AUTH_SESSION_EVENT = "wmpt-auth-session-changed";

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

function notifyAuthSessionChange(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new Event(AUTH_SESSION_EVENT));
}

export function saveAuthSession(session: AuthSession): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
  notifyAuthSessionChange();
}

export function clearAuthSession(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(AUTH_STORAGE_KEY);
  notifyAuthSessionChange();
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

function subscribeToAuthSession(onStoreChange: () => void): () => void {
  if (typeof window === "undefined") return () => {};

  const onStorage = (event: StorageEvent) => {
    if (event.key === AUTH_STORAGE_KEY || event.key === null) {
      onStoreChange();
    }
  };

  window.addEventListener("storage", onStorage);
  window.addEventListener(AUTH_SESSION_EVENT, onStoreChange);

  return () => {
    window.removeEventListener("storage", onStorage);
    window.removeEventListener(AUTH_SESSION_EVENT, onStoreChange);
  };
}

export function useAuthSession() {
  const session = useSyncExternalStore(
    subscribeToAuthSession,
    loadAuthSession,
    () => null,
  );

  return {
    data: session,
    isPending: false,
    isAuthenticated: !!session,
    setSession: (nextSession: AuthSession | null) => {
      if (nextSession) {
        saveAuthSession(nextSession);
        return;
      }
      clearAuthSession();
    },
  };
}
