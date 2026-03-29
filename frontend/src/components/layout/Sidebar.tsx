"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  PieChart,
  BarChart3,
  Sparkles,
  Settings,
  Wallet,
} from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { Separator } from "@/components/ui/separator";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

interface NavItem {
  label: string;
  href: string;
  icon: React.ElementType;
}

const NAV_ITEMS: NavItem[] = [
  { label: "Overview",    href: "/overview",     icon: LayoutDashboard },
  { label: "Portfolio",   href: "/portfolio",    icon: PieChart },
  { label: "Market Data", href: "/market-data",  icon: BarChart3 },
  { label: "AI Insights", href: "/ai-insights",  icon: Sparkles },
  { label: "Settings",    href: "/settings",     icon: Settings },
];

function NavLink({ item }: { item: NavItem }) {
  const pathname = usePathname();
  const isActive = pathname === item.href || pathname.startsWith(item.href + "/");

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Link
          href={item.href}
          className={cn(
            "group flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-150",
            isActive
              ? "bg-profit/15 text-profit"
              : "text-sidebar-foreground hover:bg-white/8 hover:text-white"
          )}
          aria-current={isActive ? "page" : undefined}
        >
          <item.icon
            className={cn(
              "h-4 w-4 shrink-0 transition-colors",
              isActive ? "text-profit" : "text-white/40 group-hover:text-white/70"
            )}
          />
          <span>{item.label}</span>

          {/* Active indicator dot */}
          {isActive && (
            <span className="ml-auto h-1.5 w-1.5 rounded-full bg-profit" />
          )}
        </Link>
      </TooltipTrigger>
      <TooltipContent side="right" className="hidden">
        {item.label}
      </TooltipContent>
    </Tooltip>
  );
}

/**
 * Fixed sidebar — dark in both light and dark mode.
 * Uses CSS variables from --sidebar-* token group.
 */
export function Sidebar() {
  return (
    <aside
      className="flex h-full w-60 shrink-0 flex-col bg-sidebar border-r border-sidebar-border"
      aria-label="Main navigation"
    >
      {/* Logo / Brand */}
      <div className="flex h-14 items-center gap-2.5 px-4 border-b border-sidebar-border">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-profit">
          <Wallet className="h-4 w-4 text-white" />
        </div>
        <div className="leading-none">
          <p className="text-sm font-bold text-white tracking-tight">WealthTracker</p>
          <p className="text-[10px] text-white/40 mt-0.5">Portfolio Management</p>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
        <p className="px-3 mb-2 text-[10px] font-semibold uppercase tracking-widest text-white/30">
          Navigation
        </p>
        {NAV_ITEMS.slice(0, 4).map((item) => (
          <NavLink key={item.href} item={item} />
        ))}

        <Separator className="my-3 bg-sidebar-border" />

        <p className="px-3 mb-2 text-[10px] font-semibold uppercase tracking-widest text-white/30">
          Account
        </p>
        <NavLink item={NAV_ITEMS[4]} />
      </nav>

      {/* Footer version tag */}
      <div className="px-4 py-3 border-t border-sidebar-border">
        <p className="text-[10px] text-white/25 text-center">Phase 1 · v0.1.0</p>
      </div>
    </aside>
  );
}
