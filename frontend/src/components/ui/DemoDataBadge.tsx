"use client";

import { FlaskConical } from "lucide-react";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export function DemoDataBadge() {
  return (
    <TooltipProvider delayDuration={0}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            className="inline-flex items-center gap-1 text-xs text-muted-foreground cursor-help"
            data-testid="demo-data-badge"
          >
            <FlaskConical className="h-3 w-3" />
            <span>Demo</span>
          </span>
        </TooltipTrigger>
        <TooltipContent side="top" className="max-w-xs text-xs">
          Showing seeded demo data. 24h % reflects a deterministic delta from
          yesterday&apos;s seed price. Real market prices are refreshed daily via
          the CI cron.
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
