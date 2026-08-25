package com.wealth.portfolio.composition;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.math.BigDecimal;

/**
 * Read-side quantity encoder: emits {@link BigDecimal#toPlainString()} as a JSON string so trailing
 * fractional zeros are preserved (D6).
 */
public final class ToPlainStringSerializer extends StdSerializer<BigDecimal> {

    public ToPlainStringSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext serializers)
            throws JacksonException {
        gen.writeString(value.toPlainString());
    }
}
