package com.wealth.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * Creates a new portfolio for the authenticated user.
     *
     * <pre>
     * POST /api/portfolio
     * X-User-Id: &lt;userId&gt;
     *
     * 201 Created — the new portfolio
     * </pre>
     */
    @PostMapping
    public ResponseEntity<PortfolioResponse> createPortfolio(
            @RequestHeader(X_USER_ID_HEADER) String userId) {
        return ResponseEntity.status(201).body(portfolioService.createPortfolio(userId));
    }

    /**
     * Adds or updates a holding in the specified portfolio.
     *
     * <pre>
     * POST /api/portfolio/{portfolioId}/holdings
     * X-User-Id: &lt;userId&gt;
     * { "ticker": "AAPL", "quantity": 10.0 }
     *
     * 201 Created — the updated portfolio
     * 404         — portfolio not found or does not belong to the user
     * </pre>
     */
    @PostMapping("/{portfolioId}/holdings")
    public ResponseEntity<PortfolioResponse> addHolding(
            @RequestHeader(X_USER_ID_HEADER) String userId,
            @PathVariable UUID portfolioId,
            @RequestBody AddHoldingRequest request) {
        return ResponseEntity.status(201)
                .body(portfolioService.addHolding(userId, portfolioId, request.ticker(), request.quantity()));
    }

    /** Request body for adding a holding. */
    public record AddHoldingRequest(String ticker, BigDecimal quantity) {}
}
