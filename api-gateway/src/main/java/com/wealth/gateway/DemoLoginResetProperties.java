package com.wealth.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Approved, fail-fast bounds for the optional demo-login reset flow. */
@ConfigurationProperties(prefix = "app.demo-login-reset")
public record DemoLoginResetProperties(
        Duration idleThreshold,
        Duration eligibilityTimeout,
        Duration resetTimeout,
        Duration overallTimeout
) {
    public DemoLoginResetProperties {
        requirePositive("idle-threshold", idleThreshold);
        requirePositive("eligibility-timeout", eligibilityTimeout);
        requirePositive("reset-timeout", resetTimeout);
        requirePositive("overall-timeout", overallTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.demo-login-reset." + name + " must be positive");
        }
    }
}
