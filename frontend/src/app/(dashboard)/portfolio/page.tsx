import type { Metadata } from "next";
import { PortfolioPageContent } from "@/components/portfolio/PortfolioPageContent";

export const metadata: Metadata = {
  title: "Portfolio",
};

/**
 * Portfolio dashboard page — Server Component shell.
 * PortfolioPageContent is a Client Component that gates all data components
 * behind a confirmed NextAuth session, preventing TanStack Query hooks from
 * firing with an empty token during the "loading" window after navigation.
 */
export default function PortfolioPage() {
  return (
    <div className="space-y-6">
      {/* ── Page header ── */}
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Portfolio</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Real-time overview of your holdings and performance.
        </p>
      </div>

      <PortfolioPageContent />
    </div>
  );
}
