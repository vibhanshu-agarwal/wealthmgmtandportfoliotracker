package com.wealth;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Architectural guard — verifies that all Spring Modulith module boundaries are intact.
 *
 * <p>This test will FAIL at build time if any module directly imports a class from another
 * module's package, enforcing the "no direct cross-module dependencies" rule.
 *
 * <p>Run this test after every structural change to catch architectural violations early.
 */
class ModulithStructureTest {

    static final ApplicationModules modules =
            ApplicationModules.of(WealthManagementApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void documentsModularStructure() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
