package com.wealth.portfolio;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

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

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public void setPortfolio(Portfolio portfolio) {
        this.portfolio = portfolio;
    }
}
