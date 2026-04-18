import type { PortfolioSummaryDTO } from "../../types/portfolio";
import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";
import { apiPath } from "@/lib/config/api";

export function fetchPortfolioSummary(
  userId: string,
  token: string,
): Promise<PortfolioSummaryDTO> {
  const params = new URLSearchParams({ userId });
  return fetchWithAuthClient<PortfolioSummaryDTO>(
    `${apiPath("/portfolio/summary")}?${params.toString()}`,
    token,
  );
}
