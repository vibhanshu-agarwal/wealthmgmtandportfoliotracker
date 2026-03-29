"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

/**
 * React Query client provider — must be a Client Component.
 * Creates the QueryClient once per browser session.
 */
export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Don't retry on 4xx errors — surface them immediately
            retry: (failureCount, error) => {
              if (error instanceof Error && error.message.includes("4")) return false;
              return failureCount < 2;
            },
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
