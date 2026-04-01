import { http, HttpResponse } from "msw";

export const handlers = [
  http.get("/api/portfolio/summary", ({ request }) => {
    const url = new URL(request.url);
    const userId = url.searchParams.get("userId") ?? "user-001";

    return HttpResponse.json({
      userId,
      portfolioCount: 1,
      totalHoldings: 4,
      totalValue: 284531.42,
    });
  }),
];
