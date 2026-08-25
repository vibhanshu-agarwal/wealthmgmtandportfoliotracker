package com.wealth.portfolio.composition;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Property-scoped {@code expectedVersion} decoder: integer JSON token only, non-negative domain
 * (D7 / P11h). Rejects float, string, boolean, null, overflow, and negative values before stateful
 * work. Explicit {@code null} is {@code invalid_version}; only an absent property is missing
 * ({@link #getAbsentValue} leaves {@code null} for {@code @NotNull}).
 */
public final class StrictExpectedVersionDeserializer extends StdDeserializer<Long> {

    public StrictExpectedVersionDeserializer() {
        super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            throw invalidVersion(p, "expectedVersion must be a JSON integer");
        }
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalidVersion(p, "expectedVersion must be a JSON integer");
        }
        final long value;
        try {
            value = p.getLongValue();
        } catch (InputCoercionException ex) {
            throw DatabindException.from(
                    p,
                    "expectedVersion is out of long range",
                    new ContractTokenException(
                            ContractErrorCode.invalid_version,
                            "expectedVersion is out of long range"));
        }
        if (value < 0L) {
            throw invalidVersion(p, "expectedVersion must be non-negative");
        }
        return value;
    }

    /**
     * Absent property → Java {@code null} so {@code @NotNull} yields {@code missing_version}.
     * Must not delegate to {@link #getNullValue}, which rejects an explicit JSON null.
     */
    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }

    @Override
    public Long getNullValue(DeserializationContext ctxt) throws JacksonException {
        throw DatabindException.from(
                ctxt,
                "expectedVersion must be a JSON integer",
                new ContractTokenException(
                        ContractErrorCode.invalid_version,
                        "expectedVersion must be a JSON integer"));
    }

    private static DatabindException invalidVersion(JsonParser p, String message)
            throws JacksonException {
        return DatabindException.from(
                p, message, new ContractTokenException(ContractErrorCode.invalid_version, message));
    }
}
