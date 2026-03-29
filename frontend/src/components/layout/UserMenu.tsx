"use client";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { LogOut, Settings, User, CreditCard, Bell } from "lucide-react";

// Mock user — will be replaced by auth session in Phase 2
const MOCK_USER = {
  name: "Alex Morgan",
  email: "alex.morgan@example.com",
  avatarUrl: "",
  plan: "Pro",
};

export function UserMenu() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 outline-none ring-ring transition-colors hover:bg-white/10 focus-visible:ring-2"
          aria-label="User menu"
        >
          <Avatar className="h-7 w-7 ring-2 ring-profit/40">
            <AvatarImage src={MOCK_USER.avatarUrl} alt={MOCK_USER.name} />
            <AvatarFallback className="bg-profit text-white text-xs font-semibold">
              {MOCK_USER.name
                .split(" ")
                .map((n) => n[0])
                .join("")}
            </AvatarFallback>
          </Avatar>
          <div className="hidden md:block text-left leading-none">
            <p className="text-xs font-semibold text-white">{MOCK_USER.name}</p>
            <p className="text-[10px] text-white/50 mt-0.5">{MOCK_USER.plan} Plan</p>
          </div>
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="w-60"
        align="end"
        sideOffset={8}
      >
        <DropdownMenuLabel className="font-normal">
          <div className="flex flex-col space-y-1">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold leading-none">{MOCK_USER.name}</p>
              <Badge variant="secondary" className="text-[10px] h-4 px-1.5">
                {MOCK_USER.plan}
              </Badge>
            </div>
            <p className="text-xs leading-none text-muted-foreground">
              {MOCK_USER.email}
            </p>
          </div>
        </DropdownMenuLabel>

        <DropdownMenuSeparator />

        <DropdownMenuGroup>
          <DropdownMenuItem>
            <User className="mr-2 h-4 w-4" />
            Profile
          </DropdownMenuItem>
          <DropdownMenuItem>
            <CreditCard className="mr-2 h-4 w-4" />
            Billing
          </DropdownMenuItem>
          <DropdownMenuItem>
            <Bell className="mr-2 h-4 w-4" />
            Notifications
          </DropdownMenuItem>
          <DropdownMenuItem>
            <Settings className="mr-2 h-4 w-4" />
            Settings
          </DropdownMenuItem>
        </DropdownMenuGroup>

        <DropdownMenuSeparator />

        <DropdownMenuItem className="text-loss focus:text-loss focus:bg-loss-muted">
          <LogOut className="mr-2 h-4 w-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
