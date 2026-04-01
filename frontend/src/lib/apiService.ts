import type { PortfolioSummaryDTO } from "../../types/portfolio";

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: "GET",
    headers: { "Content-Type": "application/json" },
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export function fetchPortfolioSummary(userId = "user-001"): Promise<PortfolioSummaryDTO> {
  const params = new URLSearchParams({ userId });
  return getJson<PortfolioSummaryDTO>(`/api/portfolio/summary?${params.toString()}`);
}
