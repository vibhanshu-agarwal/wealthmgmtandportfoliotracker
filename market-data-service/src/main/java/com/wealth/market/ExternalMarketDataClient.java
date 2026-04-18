package com.wealth.market;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public interface ExternalMarketDataClient {

    Map<String, BigDecimal> getLatestPrices(Collection<String> tickers);
}

