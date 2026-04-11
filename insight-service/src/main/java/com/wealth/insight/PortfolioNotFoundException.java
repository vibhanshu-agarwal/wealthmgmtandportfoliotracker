package com.wealth.insight;

/**
 * Thrown when the requested user has no portfolio in portfolio-service.
 */
public class PortfolioNotFoundException extends RuntimeException {

    public PortfolioNotFoundException(String userId) {
        super("No portfolio found for user: " + userId);
    }
}
