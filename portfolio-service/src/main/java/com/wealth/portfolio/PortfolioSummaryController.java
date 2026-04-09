package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioSummaryController {

    private final PortfolioService portfolioService;

    public PortfolioSummaryController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Returns a lightweight portfolio summary for the authenticated user.
     *
     * <p>The user identity is extracted from the {@code X-User-Id} header injected by the
     * API Gateway JWT filter. The hard-coded {@code "user-001"} default has been removed.
     *
     * <pre>
     * GET /api/portfolio/summary
     * X-User-Id: &lt;uuid&gt;
     *
     * 200 OK  — portfolio summary DTO
     * 400     — X-User-Id header missing
     * 404     — userId not found in users table
     * </pre>
     *
     * @param userId the authenticated user's UUID, injected by the API Gateway
     */
    @GetMapping("/summary")
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(portfolioService.getSummary(userId));
    }
}
