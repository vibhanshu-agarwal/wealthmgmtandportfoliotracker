package com.wealth.market.seed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit-level check that {@code market-data.seed.enabled=false} suppresses seed bean
 * registration. HTTP-level gating is covered by {@link MarketDataSeedEndpointGatingIT}.
 */
class MarketDataSeedGatingContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketDataSeedController.class, MarketDataSeedService.class);

    @Test
    void seedBeansAbsentWhenSeedDisabled() {
        runner.withPropertyValues("market-data.seed.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MarketDataSeedService.class);
                    assertThat(ctx).doesNotHaveBean(MarketDataSeedController.class);
                });
    }
}
