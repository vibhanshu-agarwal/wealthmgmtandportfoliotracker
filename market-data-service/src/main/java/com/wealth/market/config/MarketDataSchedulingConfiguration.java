package com.wealth.market.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring {@code @Scheduled} support for the market-data refresh pipeline.
 */
@Configuration
@EnableScheduling
public class MarketDataSchedulingConfiguration {
}
