package com.wealth.insight;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogEnforcementDefaultsTest {

    @Test
    void packagedYamlDefaultsBothEnforcementGatesToFalse() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("reject-unsupported-events: false");
        assertThat(yaml).contains("enforce-holding-invariant: false");
    }
}
