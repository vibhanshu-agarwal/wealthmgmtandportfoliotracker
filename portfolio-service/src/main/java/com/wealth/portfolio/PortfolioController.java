package com.wealth.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Deep health-check endpoint for verifying end-to-end connectivity
     * through the API Gateway. Returns a simple status response without
     * requiring authentication headers.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "portfolio-service"));
    }

    /**
     * Returns all portfolios belonging to the authenticated user.
     */
    @GetMapping
    public ResponseEntity<List<PortfolioResponse>> getPortfolios(
            @RequestHeader(X_USER_ID_HEADER) String userId) {
        return ResponseEntity.ok(portfolioService.getByUserId(userId));
    }
}
