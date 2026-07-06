"use client";

import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import React, {useState} from "react";
import {RateLimitError} from "@/lib/api/fetchWithAuth";

/**
 * Matches a 4xx HTTP status embedded in a fetchWithAuth-style error message
 * (e.g. "Request failed (404) for /api/x"). Used instead of a bare `.includes("4")`
 * substring check, which would also match unrelated digits (a 500 on a path
 * containing "4", a network error message, etc.) and suppress retries for those
 * too.
 */
const HTTP_4XX_STATUS_PATTERN = /\(4\d{2}\)/;

/**
 * Default query retry gate: don't retry 4xx errors (incl. 429 rate-limiting, via the
 * typed {@link RateLimitError}) — surface them immediately instead of hammering an
 * already-throttled or already-rejected request. Exported for unit testing.
 */
export function defaultQueryRetry(failureCount: number, error: Error): boolean {
    if (error instanceof RateLimitError) return false;
    if (HTTP_4XX_STATUS_PATTERN.test(error.message)) return false;
    return failureCount < 2;
}

/**
 * React Query client provider — must be a Client Component.
 * Creates the QueryClient once per browser session.
 */
export function QueryProvider({children}: { children: React.ReactNode }) {
    const [queryClient] = useState(
        () =>
            new QueryClient({
                defaultOptions: {
                    queries: {
                        retry: defaultQueryRetry,
                        // Consider data stale after 30 s by default
                        staleTime: 30_000,
                    },
                },
            })
    );

    return (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
}
