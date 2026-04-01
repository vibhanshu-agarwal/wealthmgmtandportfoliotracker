package com.cloud.wealth.marketdata;

import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration entry point for the Market Data bounded context.
 *
 * Bounded Context responsibilities:
 *   - Ingest real-time stock and crypto prices
 *   - Serve current market pricing to the rest of the application
 *
 * Schema: market_data_schema (logical PostgreSQL schema, isolated from other contexts)
 *
 * Inter-context communication: publishes price-update Application Events consumed by
 * portfolio-context. Direct dependencies on other context packages are FORBIDDEN.
 */
@Configuration
public class MarketDataContextConfig {
}
