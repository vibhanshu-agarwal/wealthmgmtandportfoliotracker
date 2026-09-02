package com.wealth.portfolio.seed;

import com.wealth.portfolio.composition.StrictExpectedVersionDeserializer;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Internal seed request envelope. Carries only the caller-observed portfolio version; the E2E
 * user identity is compiled into {@link PortfolioSeedController} and is never accepted from the
 * wire. A legacy body {@code userId} is ignored rather than honoured.
 *
 * <p>The property-scoped decoder rejects float, string, boolean, explicit null, negative, and
 * out-of-range input before any stateful work. An absent property stays {@code null} so
 * {@code @NotNull} reports {@code missing_version} rather than defaulting to zero — a seed that
 * invented version {@code 0} would silently overwrite a portfolio it never observed.
 */
public record PortfolioSeedRequest(
        @NotNull
        @JsonDeserialize(using = StrictExpectedVersionDeserializer.class)
        Long expectedVersion) {}
