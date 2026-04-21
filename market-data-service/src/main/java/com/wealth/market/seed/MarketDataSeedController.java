package com.wealth.market.seed;

import com.wealth.market.seed.MarketDataSeedService.SeedResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal endpoint for the Golden-State E2E seeder. Protected by
 * {@link InternalApiKeyFilter}. No JWT or {@code X-User-Id} is required \u2014 the seeder
 * always operates on the E2E test user passed in the request body.
 */
@RestController
@RequestMapping("/api/internal/market-data")
public class MarketDataSeedController {

    private final MarketDataSeedService seedService;

    public MarketDataSeedController(MarketDataSeedService seedService) {
        this.seedService = seedService;
    }

    /** Request body for the market-data seed call. */
    public record SeedRequest(String userId) {}

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(@RequestBody SeedRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userId is required"));
        }
        SeedResult result = seedService.seed(request.userId());
        return ResponseEntity.ok(Map.of("pricesUpserted", result.pricesUpserted()));
    }
}
