package com.wealth.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Returns all portfolios belonging to a user, including their asset holdings.
     *
     * <p>An empty list is returned when the user exists but has no portfolios (HTTP 200).
     * The caller is responsible for resolving whether the userId refers to a known user —
     * this module stores userId as a plain string and performs no user validation
     * (cross-module entity lookup is prohibited by the Modulith mandate).
     *
     * <pre>
     * GET /api/v1/portfolios/{userId}
     *
     * 200 OK
     * [
     *   {
     *     "id": "...",
     *     "userId": "...",
     *     "createdAt": "...",
     *     "holdings": [
     *       { "id": "...", "assetTicker": "AAPL", "quantity": 10.00000000 }
     *     ]
     *   }
     * ]
     * </pre>
     *
     * @param userId the owner's ID
     */
    @GetMapping("/{userId}")
    public ResponseEntity<List<PortfolioResponse>> getByUserId(@PathVariable String userId) {
        return ResponseEntity.ok(portfolioService.getByUserId(userId));
    }
}
