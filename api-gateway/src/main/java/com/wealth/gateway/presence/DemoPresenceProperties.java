package com.wealth.gateway.presence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.demo-presence")
public record DemoPresenceProperties(Duration ttl) {

    public DemoPresenceProperties {
        if (ttl == null || !ttl.isPositive()) {
            throw new IllegalArgumentException("app.demo-presence.ttl must be a positive duration");
        }
        if (!ttl.equals(Duration.ofSeconds(ttl.getSeconds()))) {
            throw new IllegalArgumentException(
                    "app.demo-presence.ttl must be a whole number of seconds");
        }
    }

    /** Whole-second TTL used for ZSET score age and key expiry. */
    public long ttlSeconds() {
        return ttl.getSeconds();
    }
}
