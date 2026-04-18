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
            <div className="flex h-screen overflow-hidden bg-background">
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
