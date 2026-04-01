package com.wealth.market;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketPriceController {

    private final AssetPriceRepository assetPriceRepository;

    public MarketPriceController(AssetPriceRepository assetPriceRepository) {
        this.assetPriceRepository = assetPriceRepository;
    }

    @GetMapping("/prices")
    public ResponseEntity<List<MarketPriceDto>> getPrices(
            @RequestParam(required = false) String tickers
    ) {
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
