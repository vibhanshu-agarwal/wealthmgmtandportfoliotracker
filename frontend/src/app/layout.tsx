import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { ThemeProvider } from "@/components/layout/ThemeProvider";
import { QueryProvider } from "@/components/layout/QueryProvider";
import { SessionProvider } from "@/components/layout/SessionProvider";
import { auth } from "@/auth";
import "./globals.css";
import React from "react";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: {
    default: "WealthTracker",
    template: "%s | WealthTracker",
  },
  description: "Wealth Management & Portfolio Tracker",
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  // Fetch the session server-side and pass it to SessionProvider.
  // This eliminates the client-side /api/auth/session round-trip on first render,
  // so useSession() returns "authenticated" immediately instead of "loading".
  // Per the hydration-no-flicker rule: provide data synchronously to avoid
  // the loading → data flash that causes TanStack Query hooks to fire with empty tokens.
  const session = await auth();
  console.log("[NextAuth Server] Session:", session?.user?.id ?? "null");

  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} font-sans antialiased`}
      >
        <ThemeProvider
          attribute="class"
          defaultTheme="system"
          enableSystem
          disableTransitionOnChange
        >
          <QueryProvider>
            <SessionProvider session={session}>{children}</SessionProvider>
          </QueryProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
