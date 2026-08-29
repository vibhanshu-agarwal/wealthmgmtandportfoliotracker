package com.wealth.portfolio.demo;

import com.wealth.portfolio.composition.StrictExpectedVersionDeserializer;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Demo-reset request envelope. Carries only the caller-observed portfolio version; the demo user
 * identity is compiled into {@link DemoResetService}, never accepted from the wire.
 */
public record DemoResetRequest(
        @NotNull
        @JsonDeserialize(using = StrictExpectedVersionDeserializer.class)
        Long expectedVersion) {}
