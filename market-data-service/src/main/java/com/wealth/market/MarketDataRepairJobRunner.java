package com.wealth.market;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Marker runner for the repair-Job row of the activation matrix.
 *
 * <p>The Mongo repair body is Spec A task 7. This bean must exist so
 * {@code market-data.repair.enabled=true} with refresh absent activates exactly one runner,
 * and so {@code refresh=false} + {@code repair=true} is a startup failure rather than two runners.
 */
@Component
@ConditionalOnProperty(prefix = "market-data.repair", name = "enabled", havingValue = "true")
public class MarketDataRepairJobRunner implements CommandLineRunner {

    @Override
    public void run(String... args) {
        // Implemented in Spec A task 7.
    }
}
