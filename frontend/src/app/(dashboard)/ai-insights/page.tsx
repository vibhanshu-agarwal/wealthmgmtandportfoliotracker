import type { Metadata } from "next";
import { auth } from "@/lib/auth";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { MarketSummaryGrid } from "@/components/insights/MarketSummaryGrid";
import { ChatInterface } from "@/components/insights/ChatInterface";

export const metadata: Metadata = {
  title: "AI Insights",
};

/**
 * AI Insights page — async Server Component.
 * Performs server-side session checking and composes two "use client" leaf
 * components: MarketSummaryGrid (TanStack Query) and ChatInterface (useActionState).
 */
export default async function AIInsightsPage() {
  const session = await auth.api.getSession({ headers: await headers() });
  if (!session?.user) {
    redirect("/login");
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">AI Insights</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Market summaries and AI-powered chat for your tracked tickers.
        </p>
      </div>

      <MarketSummaryGrid />
      <ChatInterface />
    </div>
  );
}
