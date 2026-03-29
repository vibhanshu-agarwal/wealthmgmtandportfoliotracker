/**
 * Portfolio module — core domain for asset holdings and valuation.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Manage asset holdings (stocks, crypto)</li>
 *   <li>Calculate total portfolio value</li>
 *   <li>Track historical performance</li>
 * </ul>
 *
 * <p>Cross-module identity: references users by plain {@code userId} (String/UUID), never via JPA
 * association to the {@code User} entity.
 * <p>Allowed dependencies: {@code market::events} only — the named interface package
 * {@code com.wealth.market.events}, which exposes {@code PriceUpdatedEvent}.
 * No other cross-module imports are permitted.
 * <p>Inter-module communication: consumes {@code PriceUpdatedEvent} from the market module
 * via {@code @ApplicationModuleListener}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Portfolio",
        allowedDependencies = {"market::events"}
)
package com.wealth.portfolio;
