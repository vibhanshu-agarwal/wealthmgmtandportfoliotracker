package com.wealth.portfolio.composition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * Composition write envelope DTO. Production controller exposure is Wave 7; this type exists so the
 * decode boundary (D7) is defined and testable now.
 *
 * <p>Quantity is intentionally not {@code @NotNull}: required/positive domain is enforced by
 * {@link QuantityDomain} at the application-operation layer after the version check, preserving
 * {@code 409} → semantic {@code 400} precedence.
 *
 * <p>Null elements inside {@code holdings} are rejected by Bean Validation ({@code List<@NotNull>})
 * as {@code malformed_request} before the write adapter is invoked.
 */
public record CompositionHoldingsRequest(
        @NotNull
        @JsonDeserialize(using = StrictExpectedVersionDeserializer.class)
        Long expectedVersion,
        @NotNull
        @Valid
        List<@NotNull HoldingIntent> holdings
) {

    public record HoldingIntent(
            @NotNull String ticker,
            @JsonDeserialize(using = StrictDecimalStringDeserializer.class)
            BigDecimal quantity
    ) {}
}
