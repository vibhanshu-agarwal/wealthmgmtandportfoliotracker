package com.wealth.insight.seed;

import com.wealth.insight.seed.InsightSeedService.EvictResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint for the Golden-State E2E seeder. Protected by
 * {@link InternalApiKeyFilter}. The caller passes the {@code portfolioId} received from
 * the portfolio-service seed response so this service does not need to call back into
 * portfolio-service (avoids a circular dependency \u2014 design doc \u00a7 6).
 */
@RestController
@RequestMapping("/api/internal/insight")
public class InsightSeedController {

    private final InsightSeedService seedService;

    public InsightSeedController(InsightSeedService seedService) {
        this.seedService = seedService;
    }

    /** Request body carrying the portfolio whose cache entry should be evicted. */
    public record SeedRequest(String userId, String portfolioId) {}

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(@RequestBody SeedRequest request) {
        if (request == null || request.portfolioId() == null || request.portfolioId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "portfolioId is required"));
        }
        try {
            UUID.fromString(request.portfolioId());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "portfolioId must be a UUID"));
        }
        EvictResult result = seedService.evict(request.portfolioId());
        return ResponseEntity.ok(Map.of("cacheKeysEvicted", result.cacheKeysEvicted()));
    }
}
