package com.wealth.portfolio.composition;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Property-scoped write-side quantity decoder: JSON string in plain decimal notation only.
 * Rejects JSON numbers and exponent notation (D6).
 */
public final class StrictDecimalStringDeserializer extends StdDeserializer<BigDecimal> {

    private static final Pattern PLAIN_DECIMAL =
            Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");

    public StrictDecimalStringDeserializer() {
        super(BigDecimal.class);
    }

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken token = p.currentToken();
        if (token != JsonToken.VALUE_STRING) {
            throw DatabindException.from(
                    p,
                    "quantity must be a JSON string in plain decimal notation",
                    new ContractTokenException(
                            ContractErrorCode.quantity_not_string,
                            "quantity must be a JSON string in plain decimal notation"));
        }
        String text = p.getText();
        if (text == null || !PLAIN_DECIMAL.matcher(text).matches()) {
            throw DatabindException.from(
                    p,
                    "quantity must be plain decimal notation without exponent",
                    new ContractTokenException(
                            ContractErrorCode.quantity_not_string,
                            "quantity must be plain decimal notation without exponent"));
        }
        return new BigDecimal(text);
    }
}
