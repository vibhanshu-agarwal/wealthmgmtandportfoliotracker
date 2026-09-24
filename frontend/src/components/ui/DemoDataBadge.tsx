"use client";

import type { ReactNode } from "react";
import { FlaskConical } from "lucide-react";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

const DEFAULT_DESCRIPTION = (
  <>
    Showing seeded demo data. 24h % reflects a deterministic delta from
    yesterday&apos;s seed price. Real market prices are refreshed daily via
    the CI cron.
  </>
);

/**
 * `description` replaces the tooltip text where the default's "24h %" wording does not apply —
 * the AI Insights card's change is over its stored price window, not 24 hours.
 */
export function DemoDataBadge({ description = DEFAULT_DESCRIPTION }: { description?: ReactNode } = {}) {
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
          {description}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
