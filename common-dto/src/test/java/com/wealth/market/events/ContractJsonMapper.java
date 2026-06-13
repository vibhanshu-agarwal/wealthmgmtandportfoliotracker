package com.wealth.market.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical Jackson 3 mapper for {@code common-dto} contract tests.
 *
 * <p>Matches the consumer-tolerant defaults used on the Kafka wire path: unknown properties
 * are ignored so forward-compatible producers do not break existing consumers.
 */
final class ContractJsonMapper {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ContractJsonMapper() {
    }

    static JsonMapper instance() {
        return MAPPER;
    }
}
