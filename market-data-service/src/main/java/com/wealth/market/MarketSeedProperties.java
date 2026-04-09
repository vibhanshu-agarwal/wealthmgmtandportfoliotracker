package com.wealth.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code market.seed.*} configuration properties.
 *
 * <p>{@code enabled} gates the seeder; {@code fixturePath} is a Spring resource path
 * (e.g. {@code classpath:fixtures/market-seed-data.json} or {@code file:/opt/seed.json}).
 */
@ConfigurationProperties(prefix = "market.seed")
public record MarketSeedProperties(
        boolean enabled,
        String fixturePath
) {}
