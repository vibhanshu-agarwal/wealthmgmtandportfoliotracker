package com.wealth.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST adapter for market price queries and updates.
 *
 * <h2>Wave 2 changes</h2>
 * <ul>
 *   <li>Response DTO now carries {@code quoteCurrency}, {@code observedAt}, reference price,
 *       and nullable {@code changeAbsolute}/{@code changePercent}/{@code changeBasis}.</li>
 *   <li>Filtered path: the silent {@code .limit(25)} truncation is replaced. If the caller
 *       requests more tickers than {@link #MAX_TICKERS_PER_REQUEST}, a {@code 400} is returned
 *       rather than silently dropping the excess. Tickers not found in the data store are
 *       included in the response as explicit "unavailable" rows (null price) rather than
 *       omitted.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market")
public class MarketPriceController {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceController.class);

    /**
     * Maximum tickers accepted in a single filtered request.
     * The golden-state portfolio holds 160 tickers; this cap is intentionally set above that
     * so a single request can cover the full portfolio. It stays below 250 to avoid payload
     * bloat under Lambda / Function URL 6 MB limits (~100 bytes × 250 = 25 KB, well within).
     */
    static final int MAX_TICKERS_PER_REQUEST = 200;

    /**
     * Hard cap on the no-filter (return-all) path.
     */
    static final int MAX_UNFILTERED_RESULTS = 100;

    /**
     * Tolerance window for labelling a change "WITHIN_24H_WINDOW" (in hours).
     */
    private static final long WINDOW_MIN_HOURS = 18;
    private static final long WINDOW_MAX_HOURS = 36;

    private final AssetPriceRepository assetPriceRepository;
    private final MarketPriceService marketPriceService;

    public MarketPriceController(AssetPriceRepository assetPriceRepository, MarketPriceService marketPriceService) {
        this.assetPriceRepository = assetPriceRepository;
        this.marketPriceService = marketPriceService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "market-data-service"));
    }

    @PostMapping("/prices/{ticker}")
    public ResponseEntity<Void> updatePrice(@PathVariable String ticker, @RequestBody BigDecimal newPrice) {
        marketPriceService.updatePrice(ticker, newPrice);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/prices")
    public ResponseEntity<?> getPrices(
            @RequestParam(required = false) String tickers
    ) {
        if (tickers == null || tickers.isBlank()) {
            // No filter — return all known prices up to the hard cap.
            var prices = assetPriceRepository.findAll();
            if (prices.size() > MAX_UNFILTERED_RESULTS) {
                log.warn("getPrices: unfiltered result set ({}) exceeds cap ({}); truncating",
                        prices.size(), MAX_UNFILTERED_RESULTS);
            }
            List<MarketPriceDto> response = prices.stream()
                    .limit(MAX_UNFILTERED_RESULTS)
                    .map(this::toDto)
                    .toList();
            return ResponseEntity.ok(response);
        }

        List<String> tickerList = Arrays.stream(tickers.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        // Reject over-limit requests explicitly rather than silently dropping tickers.
        if (tickerList.size() > MAX_TICKERS_PER_REQUEST) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Too many tickers requested",
                    "requested", tickerList.size(),
                    "limit", MAX_TICKERS_PER_REQUEST
            ));
        }

        // Fetch found tickers from the data store.
        Map<String, AssetPrice> found = assetPriceRepository.findByTickerIn(tickerList)
                .stream()
                .collect(Collectors.toMap(AssetPrice::getTicker, p -> p));

        // Build a response entry for every requested ticker — missing ones become
        // explicit "unavailable" rows rather than being omitted (Req 1 AC1, AC3).
        Set<String> requested = Set.copyOf(tickerList);
        List<MarketPriceDto> response = tickerList.stream()
                .map(ticker -> {
                    AssetPrice price = found.get(ticker);
                    if (price == null) {
                        log.debug("getPrices: ticker {} not found in data store — returning unavailable row", ticker);
                        return MarketPriceDto.unavailable(ticker);
                    }
                    return toDto(price);
                })
                .toList();

        return ResponseEntity.ok(response);
    }

    // ── Mapping helper ────────────────────────────────────────────────────────

    private MarketPriceDto toDto(AssetPrice price) {
        BigDecimal changeAbsolute = null;
        BigDecimal changePercent = null;
        String changeBasis = null;

        BigDecimal current = price.getCurrentPrice();
        BigDecimal ref = price.getPreviousReferencePrice();
        Instant refAt = price.getPreviousReferenceAt();

        if (current != null && ref != null && refAt != null) {
            changeAbsolute = current.subtract(ref).setScale(4, RoundingMode.HALF_UP);

            if (ref.compareTo(BigDecimal.ZERO) != 0) {
                changePercent = current.subtract(ref)
                        .divide(ref, new MathContext(10, RoundingMode.HALF_UP))
                        .multiply(new BigDecimal("100"))
                        .setScale(4, RoundingMode.HALF_UP);
            } else {
                changePercent = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }

            // Determine the label based on how far back the reference is.
            long hoursBack = Duration.between(refAt, Instant.now()).toHours();
            if (hoursBack >= WINDOW_MIN_HOURS && hoursBack <= WINDOW_MAX_HOURS) {
                changeBasis = "WITHIN_24H_WINDOW";
            } else {
                changeBasis = "SINCE_PREVIOUS_SNAPSHOT";
            }
        }

        return MarketPriceDto.fromAssetPrice(price, changeAbsolute, changePercent, changeBasis);
    }
}
