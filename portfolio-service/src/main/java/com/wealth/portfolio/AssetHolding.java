package com.wealth.portfolio;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a single asset holding within a portfolio.
 *
 * <p>Wave 2 additions: cost-basis fields to support real unrealized P&L computation.
 * All cost-basis columns are nullable — absence means "unavailable" (not $0).
 */
@Entity
@Table(
        name = "asset_holdings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"portfolio_id", "asset_ticker"})
)
public class AssetHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(nullable = false)
    private String assetTicker;

    @Column(nullable = false)
    private BigDecimal quantity;

    /**
     * Average cost per unit in {@link #costBasisCurrency}. Null means unavailable — the
     * analytics service will not compute unrealized P&L for this holding.
     */
    @Column(precision = 19, scale = 4)
    private BigDecimal avgCostBasis;

    /**
     * ISO currency code for {@link #avgCostBasis} (may differ from the asset's quote currency).
     */
    @Column(length = 10)
    private String costBasisCurrency;

    /**
     * How the cost basis was captured: {@code "SEED"}, {@code "ADD_TIME"}, etc.
     */
    @Column(length = 32)
    private String costBasisSource;

    /** When the cost basis was captured / last updated. */
    private Instant costBasisAsOf;

    protected AssetHolding() {}

    public AssetHolding(Portfolio portfolio, String assetTicker, BigDecimal quantity) {
        this.portfolio = portfolio;
        this.assetTicker = assetTicker;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public Portfolio getPortfolio() { return portfolio; }
    public String getAssetTicker() { return assetTicker; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAvgCostBasis() { return avgCostBasis; }
    public String getCostBasisCurrency() { return costBasisCurrency; }
    public String getCostBasisSource() { return costBasisSource; }
    public Instant getCostBasisAsOf() { return costBasisAsOf; }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public void setPortfolio(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    public void setAvgCostBasis(BigDecimal avgCostBasis) {
        this.avgCostBasis = avgCostBasis;
    }

    public void setCostBasisCurrency(String costBasisCurrency) {
        this.costBasisCurrency = costBasisCurrency;
    }

    public void setCostBasisSource(String costBasisSource) {
        this.costBasisSource = costBasisSource;
    }

    public void setCostBasisAsOf(Instant costBasisAsOf) {
        this.costBasisAsOf = costBasisAsOf;
    }
}
