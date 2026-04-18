import { MarketDataPageContent } from "@/components/market/MarketDataPageContent";

export default function MarketDataPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight">Market Data</h1>
      <MarketDataPageContent />
    </div>
  );
}
