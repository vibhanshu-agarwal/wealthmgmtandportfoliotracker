"use client";

import React, { useState } from "react";
import { useRouter } from "next/navigation";
import { signupWithBackend } from "@/lib/auth/session";
import { validateSignup, SignupValidationError, type SignupField } from "@/lib/auth/signupValidator";

const SIGNUP_TIMEOUT_MS = 10_000;

function serverErrorMessage(status: number, field?: string): string {
  if (status === 409) {
    return "An account with this email already exists.";
  }
  if (status === 400) {
    if (field === "email") return "Enter a valid email address.";
    if (field === "password") return "Password must be at least 12 characters (max 72 bytes).";
    if (field === "name") return "Enter a name between 1 and 100 characters.";
    return "Please check your input and try again.";
  }
  return "Signup could not be completed. Please try again.";
}

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<SignupField | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.SyntheticEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setFieldError(null);

    const form = new FormData(e.currentTarget);
    const submittedEmail = (form.get("email") as string) ?? "";
    const submittedPassword = (form.get("password") as string) ?? "";
    const submittedName = (form.get("name") as string) ?? "";
    setEmail(submittedEmail);
    setName(submittedName);

    try {
      validateSignup(submittedEmail, submittedPassword, submittedName);
    } catch (err) {
      if (err instanceof SignupValidationError) {
        setFieldError(err.field);
        setError(err.message);
      } else {
        setError("Please check your input and try again.");
      }
      return;
    }

    setLoading(true);
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), SIGNUP_TIMEOUT_MS);

    try {
      await signupWithBackend(
        submittedEmail,
        submittedPassword,
        submittedName.trim(),
        controller.signal,
      );
      router.push("/overview");
    } catch (err) {
      const status = (err as { status?: number })?.status;
      const field = (err as { field?: string })?.field;
      if (status === 400 || status === 409) {
        setError(serverErrorMessage(status, field));
      } else {
        setError("Signup could not be completed. Please try again.");
      }
    } finally {
      clearTimeout(timeoutId);
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background">
      <div className="w-full max-w-sm space-y-6 rounded-xl border border-border bg-card p-8 shadow-sm">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Create an account
          </h1>
          <p className="text-sm text-muted-foreground">
            Sign up to start tracking your portfolio.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1">
            <label htmlFor="name" className="text-sm font-medium text-foreground">
              Name
            </label>
            <input
              id="name"
              name="name"
              type="text"
              required
              autoComplete="name"
              defaultValue={name}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="Jane Doe"
            />
            {fieldError === "name" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <div className="space-y-1">
            <label htmlFor="email" className="text-sm font-medium text-foreground">
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              required
              autoComplete="email"
              defaultValue={email}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="you@example.com"
            />
            {fieldError === "email" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <div className="space-y-1">
            <label htmlFor="password" className="text-sm font-medium text-foreground">
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              required
              autoComplete="new-password"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="At least 12 characters"
            />
            {fieldError === "password" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          {!fieldError && error && <p className="text-sm text-destructive">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {loading ? "Creating account…" : "Create account"}
          </button>
        </form>

        <p className="text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <a href="/login" className="font-medium text-primary hover:underline">
            Sign in
          </a>
        </p>
      </div>
    </main>
  );
}
