import {TooltipProvider} from "@/components/ui/tooltip";
import {Sidebar} from "./Sidebar";
import {Header} from "./Header";
import React from "react";

interface DashboardLayoutProps {
    children: React.ReactNode;
}

/**
 * DashboardLayout — Server Component shell.
 * Sidebar + Header are Client Components; this wrapper stays a Server Component
 * so the page tree can be streamed from the server.
 */
export function DashboardLayout({children}: DashboardLayoutProps) {
    return (
        <TooltipProvider delayDuration={300}>
            {/* `relative` makes this clipping wrapper the containing block for absolutely positioned
                descendants (every `sr-only` span), so they are clipped here instead of growing the
                document's scroll area. `overflow-clip`, not `-hidden`: hidden is still a scroll container,
                so scrollIntoView() (chat auto-scroll, router navigation) would scroll the whole shell. */}
            <div className="relative flex h-screen overflow-clip bg-background">
                {/* Fixed sidebar */}
                <Sidebar/>

                {/* Main content area */}
                <div className="flex flex-1 flex-col overflow-hidden">
                    {/* Fixed top header */}
                    <Header/>

                    {/* Scrollable page content */}
                    <main
                        className="flex-1 overflow-y-auto p-6"
                        id="main-content"
                        tabIndex={-1}
                    >
                        <div className="mx-auto max-w-7xl animate-fade-in">
                            {children}
                        </div>
                    </main>
                </div>
            </div>
        </TooltipProvider>
    );
}
