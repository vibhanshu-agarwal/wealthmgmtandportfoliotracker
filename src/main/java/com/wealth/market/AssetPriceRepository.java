package com.wealth.market;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AssetPriceRepository extends JpaRepository<AssetPrice, String> {

    List<AssetPrice> findByTickerIn(List<String> tickers);
}
