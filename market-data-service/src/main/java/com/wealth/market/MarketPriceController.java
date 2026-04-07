package com.wealth.market;

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
        // If no ticker filter is provided we return all known prices.
        var prices = (tickers == null || tickers.isBlank())
                ? assetPriceRepository.findAll()
                : assetPriceRepository.findByTickerIn(Arrays.stream(tickers.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList());

        var response = prices.stream()
                .map(p -> new MarketPriceDto(p.getTicker(), p.getCurrentPrice(), p.getUpdatedAt()))
                .toList();

        return ResponseEntity.ok(response);
    }
}
