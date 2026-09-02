package com.wealth.portfolio.seed;

import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     * Converges the E2E user's portfolio to the golden state: one portfolio with one holding per
     * active catalogue ticker. The response body carries {@code portfolioId} so the Playwright
     * caller can chain it into subsequent seeder calls (market-data-service, insight-service)
     * per the design doc.
     *
     * <p>The caller must supply the version it observed. The identity is not negotiable: the
     * compiled-in E2E target is used regardless of any body, header, or query identity, and a
     * legacy body {@code userId} is ignored. Only {@code expectedVersion} is an effective input.
     *
     * <p>A stale version yields Requirement 7's {@code portfolio_version_conflict} envelope from
     * {@link com.wealth.portfolio.GlobalExceptionHandler}; this endpoint neither retries nor
     * reads a version of its own.
     *
     * <p>This endpoint is reachable in production and is invoked there on a schedule. It
     * writes portfolios and holdings only — never {@code market_prices} or
     * {@code market_price_history}. The removed {@code marketPricesUpserted} response field
     * is deliberately not replaced; there is no price write to report.
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(
            @Valid @RequestBody PortfolioSeedRequest request) {
        SeedResult result = seedService.seed(E2E_USER_ID, request.expectedVersion());
        UUID portfolioId = result.portfolioId();
        return ResponseEntity.ok(Map.of(
                "userId", E2E_USER_ID,
                "portfolioId", portfolioId.toString(),
                "holdingsInserted", result.holdingsInserted()
        ));
    }
}
