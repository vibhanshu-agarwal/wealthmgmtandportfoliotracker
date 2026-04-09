import { http, HttpResponse } from "msw";

function requireBearer(request: Request): HttpResponse | null {
  const auth = request.headers.get("Authorization");
  if (!auth?.startsWith("Bearer ")) {
    return new HttpResponse(null, { status: 401 });
  }
  return null;
}

export const handlers = [
  http.get("/api/portfolio", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json([
      {
        id: "portfolio-001",
        userId: "user-001",
        createdAt: new Date().toISOString(),
        holdings: [],
      },
    ]);
  }),

  http.get("/api/portfolio/summary", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    const url = new URL(request.url);
    const userId = url.searchParams.get("userId") ?? "user-001";

    return HttpResponse.json({
      userId,
      portfolioCount: 1,
      totalHoldings: 4,
      totalValue: 284531.42,
    });
  }),

  http.get("/api/market/prices", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json([]);
  }),
];
