package com.wealth.market;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "market_prices")
public class AssetPrice {

    @Id
    private String ticker;

    @Column(nullable = false)
    private BigDecimal currentPrice;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AssetPrice() {}

    public AssetPrice(String ticker, BigDecimal currentPrice) {
        this.ticker = ticker;
        this.currentPrice = currentPrice;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    private void onSave() {
        this.updatedAt = Instant.now();
    }

    public String getTicker() { return ticker; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }
}
