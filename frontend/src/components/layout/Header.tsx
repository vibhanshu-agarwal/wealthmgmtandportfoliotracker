"use client";

import { Bell } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { ThemeToggle } from "./ThemeToggle";
import { UserMenu } from "./UserMenu";
import { PortfolioTicker } from "./PortfolioTicker";

/**
 * Fixed top header strip.
 * Contains: Ticker | Actions (Notifications, ThemeToggle, UserMenu)
 */
export function Header() {
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-sidebar-border bg-sidebar px-4">
      {/* Scrolling ticker — takes remaining width */}
      <PortfolioTicker />

      {/* Right-side controls */}
      <div className="flex items-center gap-1.5 shrink-0">
        {/* Notifications */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="relative h-8 w-8 text-sidebar-foreground hover:bg-white/10"
              aria-label="Notifications"
            >
              <Bell className="h-4 w-4" />
              {/* Unread badge */}
              <Badge className="absolute -top-1 -right-1 h-4 w-4 p-0 flex items-center justify-center text-[9px] bg-profit border-0">
                3
              </Badge>
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">
            <p>Notifications (3 unread)</p>
          </TooltipContent>
        </Tooltip>

        <ThemeToggle />

        <div className="ml-1 h-5 w-px bg-white/15" aria-hidden />

        <UserMenu />
      </div>
    </header>
  );
}
