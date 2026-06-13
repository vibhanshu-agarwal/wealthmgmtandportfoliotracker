package com.wealth.market.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only Jackson 3 mapper for {@code common-dto} structural compatibility checks.
 *
 * <p>This mapper proves that {@link PriceUpdatedEvent} can be (de)serialized under Jackson 3
 * with consumer-tolerant defaults (unknown properties ignored). It is <strong>not</strong> a
 * guarantee of production Kafka wire fidelity — that contract is pinned in
 * {@code portfolio-service} (consumer, Task 6.2) and {@code market-data-service} (producer,
 * Task 6.5) using the real Spring Kafka serializer stack.
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
