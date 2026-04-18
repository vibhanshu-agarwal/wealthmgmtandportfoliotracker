package com.wealth.market;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.math.BigDecimal;
import java.time.Instant;

@Document(collection = "market_prices")
public class AssetPrice {

    @Id
    private String ticker;

    private BigDecimal currentPrice;

    private Instant updatedAt;

    public AssetPrice() {
        this.updatedAt = Instant.now();
    }

    public AssetPrice(String ticker, BigDecimal currentPrice) {
        this.ticker = ticker;
        this.currentPrice = currentPrice;
        this.updatedAt = Instant.now();
    }

    public void onSave() {
        this.updatedAt = Instant.now();
    }

    public String getTicker() { return ticker; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
        this.onSave();
    }
}
