package com.wealth;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    @Test
    void shouldHaveNoCircularDependencies() {
        ApplicationModules.of(WealthManagementApplication.class).verify();
    }
}
