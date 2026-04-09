import type { PortfolioSummaryDTO } from "../../types/portfolio";
import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";

export function fetchPortfolioSummary(
  userId: string,
  token: string,
): Promise<PortfolioSummaryDTO> {
  const params = new URLSearchParams({ userId });
  return fetchWithAuthClient<PortfolioSummaryDTO>(
    `/api/portfolio/summary?${params.toString()}`,
    token,
  );
}
