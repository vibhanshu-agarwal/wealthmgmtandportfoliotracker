package com.cloud.wealth.portfolio;

import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration entry point for the Portfolio bounded context.
 *
 * Bounded Context responsibilities:
 *   - Manage asset holdings (stocks, crypto)
 *   - Calculate total portfolio value
 *   - Track historical performance
 *
 * Schema: portfolio_schema (logical PostgreSQL schema, isolated from other contexts)
 *
 * Inter-context communication: publishes/consumes Spring Application Events only.
 * Direct dependencies on other context packages are FORBIDDEN.
 */
@Configuration
public class PortfolioContextConfig {
}
