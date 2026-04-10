package com.wealth.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Returns all portfolios belonging to the authenticated user.
     *
     * <p>The user identity is extracted from the {@code X-User-Id} header injected by the
     * API Gateway JWT filter. Callers must not supply this header directly — it is stripped
     * and re-injected by the gateway after JWT validation.
     *
     * <pre>
     * GET /api/portfolio
     * X-User-Id: &lt;uuid&gt;
     *
     * 200 OK  — list of portfolios (empty list if user has no portfolios)
     * 400     — X-User-Id header missing (request bypassed the API Gateway)
     * 404     — userId not found in users table
     * </pre>
     *
     * @param userId the authenticated user's UUID, injected by the API Gateway
     */
    @GetMapping
    public ResponseEntity<List<PortfolioResponse>> getPortfolios(
            @RequestHeader(X_USER_ID_HEADER) String userId) {
        return ResponseEntity.ok(portfolioService.getByUserId(userId));
    }
}
