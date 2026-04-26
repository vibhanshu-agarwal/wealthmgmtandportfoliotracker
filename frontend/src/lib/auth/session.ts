"use client";

import * as React from "react";
import { apiPath } from "@/lib/config/api";

const AUTH_STORAGE_KEY = "wmpt.auth.session";
const AUTH_SESSION_EVENT = "wmpt-auth-session-changed";

export interface AuthSession {
  token: string;
  userId: string;
  email: string;
  name: string;
}

export type LoginErrorKind = "http" | "network" | "invalid-response";

export class LoginError extends Error {
  constructor(
    message: string,
    readonly kind: LoginErrorKind,
    readonly status?: number,
  ) {
    super(message);
    this.name = "LoginError";
  }
}

export function getLoginErrorMessage(error: unknown): string {
  if (error instanceof LoginError) {
    if (error.status === 401) {
      return "Invalid username or password.";
    }
    if (error.kind === "network") {
      return "Unable to reach the login service. Please try again.";
    }
    if (error.kind === "invalid-response") {
      return "Login response was invalid. Please try again.";
    }
    if (error.status && error.status >= 500) {
      return "Login service is temporarily unavailable. Please try again shortly.";
    }
  }
  return "Login service is temporarily unavailable. Please try again shortly.";
}

/** Normalise API / stored JSON (camelCase or snake_case) into AuthSession. */
function coerceSession(parsed: Record<string, unknown>): AuthSession | null {
  const token =
    typeof parsed.token === "string"
      ? parsed.token
      : typeof parsed.access_token === "string"
        ? parsed.access_token
        : "";
  const userId =
    typeof parsed.userId === "string"
      ? parsed.userId
      : typeof parsed.user_id === "string"
        ? parsed.user_id
        : typeof parsed.sub === "string"
          ? parsed.sub
          : "";
  const email = typeof parsed.email === "string" ? parsed.email : "";
  const name =
    typeof parsed.name === "string"
      ? parsed.name
      : typeof parsed.fullName === "string"
        ? parsed.fullName
        : "User";
  if (!token || !userId || !email) {
    return null;
  }
  return { token, userId, email, name };
}

function parseStoredSession(raw: string | null): AuthSession | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return coerceSession(parsed);
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
  let response: Response;
  try {
    response = await fetch(apiPath("/auth/login"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new LoginError("Login request failed", "network");
  }

  if (!response.ok) {
    throw new LoginError(`Login failed (${response.status})`, "http", response.status);
  }

  let raw: Record<string, unknown>;
  try {
    raw = (await response.json()) as Record<string, unknown>;
  } catch {
    throw new LoginError("Login response was not valid JSON", "invalid-response");
  }
  const parsed = coerceSession(raw);
  if (!parsed) {
    throw new LoginError("Login response missing token, userId, or email", "invalid-response");
  }
  saveAuthSession(parsed);
  return parsed;
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

/**
 * LocalStorage-backed session. Uses client-first state + layout effect instead of
 * useSyncExternalStore so static-export hydration does not race with the first paint
 * (which previously left session null and triggered /login redirects before data loaded).
 */
export function useAuthSession() {
  const [session, setSession] = React.useState<AuthSession | null>(() =>
    typeof window === "undefined" ? null : loadAuthSession(),
  );

  React.useLayoutEffect(() => {
    setSession(loadAuthSession());
    return subscribeToAuthSession(() => {
      setSession(loadAuthSession());
    });
  }, []);

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
