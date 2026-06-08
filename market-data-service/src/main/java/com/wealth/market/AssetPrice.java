package com.wealth.market;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MongoDB document representing the latest known price for a single asset.
 *
 * <p>Wave 2 additions:
 * <ul>
 *   <li>{@code quoteCurrency} — ISO currency code for the price (e.g. "USD", "INR").</li>
 *   <li>{@code previousReferencePrice} — the prior {@code currentPrice} rolled forward when
 *       a new observation is persisted; null until the first update after initial seed.</li>
 *   <li>{@code previousReferenceAt} — the {@code updatedAt} of the previous observation.</li>
 * </ul>
 * These fields enable the market-data service to compute honest change values without
 * cross-service DB access, and to publish enriched {@code PriceUpdatedEvent}s.
 */
@Document(collection = "market_prices")
public class AssetPrice {

    @Id
    private String ticker;

    private BigDecimal currentPrice;

    /** ISO currency code the price is quoted in (e.g. "USD", "INR"). Nullable until backfilled. */
    private String quoteCurrency;

    private Instant updatedAt;

    /**
     * The prior currentPrice, rolled forward when a new observation is persisted.
     * Null when no prior observation exists (i.e. on first seed/update).
     */
    private BigDecimal previousReferencePrice;

    /**
     * The observation time ({@code updatedAt}) of {@link #previousReferencePrice}.
     * Null when no prior observation exists.
     */
    private Instant previousReferenceAt;

    public AssetPrice() {
        this.updatedAt = Instant.now();
    }

    public AssetPrice(String ticker, BigDecimal currentPrice) {
        this.ticker = ticker;
        this.currentPrice = currentPrice;
        this.updatedAt = Instant.now();
    }

    /**
     * Records a new price observation. Before overwriting {@code currentPrice}, the prior
     * price and timestamp are rolled into the reference fields so callers can derive change.
     *
     * @param newPrice       the new price in {@code quoteCurrency}
     * @param newObservedAt  the time of this observation (must not be receive-time fabrication)
     */
    public void recordNewObservation(BigDecimal newPrice, Instant newObservedAt) {
        // Roll the current price/time forward to become the reference before overwriting.
        if (this.currentPrice != null && this.updatedAt != null) {
            this.previousReferencePrice = this.currentPrice;
            this.previousReferenceAt = this.updatedAt;
        }
        this.currentPrice = newPrice;
        this.updatedAt = newObservedAt;
    }

    /** Legacy mutator retained for backward compatibility; does not roll reference forward. */
    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
        this.updatedAt = Instant.now();
    }

    public String getTicker() { return ticker; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public BigDecimal getPreviousReferencePrice() { return previousReferencePrice; }
    public Instant getPreviousReferenceAt() { return previousReferenceAt; }

    public void setQuoteCurrency(String quoteCurrency) { this.quoteCurrency = quoteCurrency; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setPreviousReferencePrice(BigDecimal previousReferencePrice) { this.previousReferencePrice = previousReferencePrice; }
    public void setPreviousReferenceAt(Instant previousReferenceAt) { this.previousReferenceAt = previousReferenceAt; }
}
