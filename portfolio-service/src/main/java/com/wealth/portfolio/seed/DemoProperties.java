package com.wealth.portfolio.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Typed binding for {@code app.demo.*}.
 *
 * <p>{@code seedOnStartup} gates {@link DemoPortfolioInitializer}; the default is {@code false}
 * in {@code application.yml} so an ordinary deploy is inert. {@code costBasisAnchor} is the
 * fixed {@code cost_basis_as_of} written by {@link PortfolioSeedService} so desired holding
 * state does not move between boots.
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(
        boolean seedOnStartup,
        Instant costBasisAnchor
) {
    public DemoProperties {
        Objects.requireNonNull(costBasisAnchor, "app.demo.cost-basis-anchor must be set");
        costBasisAnchor = costBasisAnchor.truncatedTo(ChronoUnit.MILLIS);
    }
}
