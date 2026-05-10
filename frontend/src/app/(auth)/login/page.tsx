"use client";

import React, { useState } from "react";
import { getLoginErrorMessage, loginWithBackend } from "@/lib/auth/session";
import { useRouter } from "next/navigation";

// Demo credentials injected at build time (from NEXT_PUBLIC_DEMO_EMAIL / NEXT_PUBLIC_DEMO_PASSWORD).
// Pre-populates the login form so recruiters can sign in with a single click.
const DEMO_EMAIL = process.env.NEXT_PUBLIC_DEMO_EMAIL ?? "";
const DEMO_PASSWORD = process.env.NEXT_PUBLIC_DEMO_PASSWORD ?? "";

export default function LoginPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [bannerVisible, setBannerVisible] = useState(true);

  async function handleSubmit(e: React.SyntheticEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    const form = new FormData(e.currentTarget);
    try {
      await loginWithBackend(
        form.get("email") as string,
        form.get("password") as string,
      );
      router.push("/overview");
    } catch (err) {
      setError(getLoginErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen flex-col bg-background">
      {bannerVisible && (
        <div
          role="alert"
          className="flex items-start gap-3 border-b border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900"
        >
          <span className="mt-0.5 shrink-0 text-base" aria-hidden="true">
            ⚠️
          </span>
          <p className="flex-1">
            <strong>Infrastructure Notice:</strong> The backend microservices
            for this application are currently experiencing strict serverless
            concurrency throttling on AWS. A quota increase request is pending,
            alongside a contingency plan to migrate to Azure Container Apps.
            During this window, you may experience timeouts or empty data views.
            Thank you for your patience while this multi-cloud architecture is
            optimized.
          </p>
          <button
            type="button"
            aria-label="Dismiss notice"
            onClick={() => setBannerVisible(false)}
            className="shrink-0 rounded p-1 text-amber-700 hover:bg-amber-100 hover:text-amber-900"
          >
            ✕
          </button>
        </div>
      )}

      <div className="flex flex-1 items-center justify-center">
        <div className="w-full max-w-sm space-y-6 rounded-xl border border-border bg-card p-8 shadow-sm">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Sign in
          </h1>
          <p className="text-sm text-muted-foreground">
            Sign in with your configured demo credentials.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1">
            <label
              htmlFor="email"
              className="text-sm font-medium text-foreground"
            >
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              required
              autoComplete="email"
              defaultValue={DEMO_EMAIL}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="dev@localhost.local"
            />
          </div>

          <div className="space-y-1">
            <label
              htmlFor="password"
              className="text-sm font-medium text-foreground"
            >
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              required
              autoComplete="current-password"
              defaultValue={DEMO_PASSWORD}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="••••••••"
            />
          </div>

          {error && <p className="text-sm text-destructive">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>
        </div>
      </div>
    </main>
  );
}
