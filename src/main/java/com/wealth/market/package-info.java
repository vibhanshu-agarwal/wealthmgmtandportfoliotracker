/**
 * Market module — real-time stock and crypto price ingestion and serving.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Ingest real-time asset prices</li>
 *   <li>Persist current prices for query</li>
 *   <li>Publish {@code PriceUpdatedEvent} when prices change</li>
 * </ul>
 *
 * <p>Allowed dependencies: none (no other com.wealth.* module may be imported directly).
 * <p>Inter-module communication: publishes {@code PriceUpdatedEvent} via
 * {@code ApplicationEventPublisher}. Consumed by the portfolio module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Market Data",
        allowedDependencies = {}
)
package com.wealth.market;
