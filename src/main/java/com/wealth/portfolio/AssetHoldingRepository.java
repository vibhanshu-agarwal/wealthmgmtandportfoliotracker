package com.wealth.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetHoldingRepository extends JpaRepository<AssetHolding, UUID> {

    List<AssetHolding> findByPortfolio(Portfolio portfolio);

    Optional<AssetHolding> findByPortfolioAndAssetTicker(Portfolio portfolio, String assetTicker);

    // Used by the price-update listener to find every holding affected by a ticker change
    List<AssetHolding> findByAssetTicker(String assetTicker);
}
