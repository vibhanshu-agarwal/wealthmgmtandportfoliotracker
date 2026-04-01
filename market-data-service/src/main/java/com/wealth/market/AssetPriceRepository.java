package com.wealth.market;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface AssetPriceRepository extends MongoRepository<AssetPrice, String> {

    List<AssetPrice> findByTickerIn(List<String> tickers);
}
