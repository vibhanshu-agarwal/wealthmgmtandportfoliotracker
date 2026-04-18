import type { Metadata } from "next";
import { MarketSummaryGrid } from "@/components/insights/MarketSummaryGrid";
import { ChatInterface } from "@/components/insights/ChatInterface";

export const metadata: Metadata = {
  title: "AI Insights",
};

export default function AIInsightsPage() {
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
