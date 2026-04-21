package com.wealth.portfolio.seed;

import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint for the Golden-State E2E seeder. Protected by
 * {@link InternalApiKeyFilter} \u2014 no user identity is required and the
 * {@code X-User-Id} header is ignored because the seeder always operates on
 * the dedicated E2E test user (requirement 8.1 / design doc).
 */
@RestController
@RequestMapping("/api/internal/portfolio")
public class PortfolioSeedController {

    /** Fixed UUID of the E2E test user (see V10__Seed_E2E_Test_User.sql). */
    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    private final PortfolioSeedService seedService;

    public PortfolioSeedController(PortfolioSeedService seedService) {
        this.seedService = seedService;
    }

    /**
     * Wipes and re-seeds the E2E user's portfolio: 1 portfolio with 160 holdings
     * plus 160 deterministic {@code market_prices} rows. The response body carries
     * {@code portfolioId} so the Playwright caller can chain it into subsequent
     * seeder calls (market-data-service, insight-service) per the design doc.
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        SeedResult result = seedService.seed(E2E_USER_ID);
        UUID portfolioId = result.portfolioId();
        return ResponseEntity.ok(Map.of(
                "userId", E2E_USER_ID,
                "portfolioId", portfolioId.toString(),
                "holdingsInserted", result.holdingsInserted(),
                "marketPricesUpserted", result.marketPricesUpserted()
        ));
    }
}
