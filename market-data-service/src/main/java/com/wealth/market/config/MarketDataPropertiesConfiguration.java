package com.wealth.market.config;

import com.wealth.market.BaselineTickerProperties;
import com.wealth.market.ExternalMarketDataProperties;
import com.wealth.market.MarketSeedProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers typed configuration for market bootstrap, baseline tickers, and the external quote provider.
 * <p>
 * Companion beans (scheduled refresh, Yahoo client, seeders, startup hydration) live under
 * {@code com.wealth.market} and are toggled via {@code market-data.*} and profile-specific YAML.
 */
@Configuration
@EnableConfigurationProperties({
        MarketSeedProperties.class,
        BaselineTickerProperties.class,
        ExternalMarketDataProperties.class
})
public class MarketDataPropertiesConfiguration {
}
