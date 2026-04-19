package com.wealth.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * REST adapter for market price queries and updates.
 */
@RestController
@RequestMapping("/api/market")
public class MarketPriceController {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceController.class);

    /**
     * Maximum tickers accepted in a single filtered request.
     * Lambda Function URLs have a 6 MB response payload limit; capping the result
     * set prevents future 502s from payload truncation as the ticker catalogue grows.
     */
    static final int MAX_TICKERS_PER_REQUEST = 25;

    /**
     * Hard cap on the no-filter (return-all) path.
     * 100 rows × ~100 bytes/row ≈ 10 KB — well within the 6 MB limit.
     * This creates a predictable upper bound as baseline tickers are added over time.
     */
    static final int MAX_UNFILTERED_RESULTS = 100;

    private final AssetPriceRepository assetPriceRepository;
    private final MarketPriceService marketPriceService;

    public MarketPriceController(AssetPriceRepository assetPriceRepository, MarketPriceService marketPriceService) {
        this.assetPriceRepository = assetPriceRepository;
        this.marketPriceService = marketPriceService;
    }

    @PostMapping("/prices/{ticker}")
    public ResponseEntity<Void> updatePrice(@PathVariable String ticker, @RequestBody BigDecimal newPrice) {
        marketPriceService.updatePrice(ticker, newPrice);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/prices")
    public ResponseEntity<List<MarketPriceDto>> getPrices(
            @RequestParam(required = false) String tickers
    ) {
        List<MarketPriceDto> response;

        if (tickers == null || tickers.isBlank()) {
            // No filter — return all known prices up to the hard cap.
            var prices = assetPriceRepository.findAll();
            if (prices.size() > MAX_UNFILTERED_RESULTS) {
                log.warn("getPrices: unfiltered result set ({}) exceeds cap ({}); truncating",
                        prices.size(), MAX_UNFILTERED_RESULTS);
            }
            response = prices.stream()
                    .limit(MAX_UNFILTERED_RESULTS)
                    .map(p -> new MarketPriceDto(p.getTicker(), p.getCurrentPrice(), p.getUpdatedAt()))
                    .toList();
        } else {
            List<String> tickerList = Arrays.stream(tickers.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(MAX_TICKERS_PER_REQUEST)
                    .toList();

            if (tickerList.size() < tickers.split(",").length) {
                log.warn("getPrices: request had more than {} tickers; excess silently dropped",
                        MAX_TICKERS_PER_REQUEST);
            }

            response = assetPriceRepository.findByTickerIn(tickerList).stream()
                    .map(p -> new MarketPriceDto(p.getTicker(), p.getCurrentPrice(), p.getUpdatedAt()))
                    .toList();
        }

        return ResponseEntity.ok(response);
    }
}
