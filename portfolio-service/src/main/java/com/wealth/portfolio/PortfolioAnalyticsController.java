package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

/**
 * Exposes the portfolio analytics endpoint.
 *
 * <pre>
 * GET /api/portfolio/analytics
 * X-User-Id: &lt;uuid&gt;
 *
 * 200 OK  — PortfolioAnalyticsDto
 * 400     — X-User-Id header missing (request bypassed the API Gateway)
 * 404     — userId not found in users table
 * </pre>
 *
 * <p>User identity is extracted exclusively from the {@code X-User-Id} header injected by the
 * API Gateway JWT filter. The endpoint never accepts a {@code userId} query parameter.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioAnalyticsController {

    private final PortfolioAnalyticsService analyticsService;

    public PortfolioAnalyticsController(PortfolioAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics")
    public ResponseEntity<PortfolioAnalyticsDto> getAnalytics(
            @RequestHeader(X_USER_ID_HEADER) String userId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(userId));
    }
}
