package com.wealth.gateway;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class DemoLoginResetArchitectureTest {
    @Test
    void loginReachableApplicationClassesCannotUseBlockingHttpOrDetachedSubscriptions() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wealth");
        var pending = new ArrayDeque<JavaClass>();
        var seen = new HashSet<String>();
        pending.add(classes.get(AuthController.class));
        while (!pending.isEmpty()) {
            JavaClass type = pending.remove();
            if (!seen.add(type.getName())) continue;
            type.getMethodCallsFromSelf().forEach(call -> {
                String owner = call.getTargetOwner().getName();
                String name = call.getTarget().getName();
                assertThat(owner).as(call.getDescription()).doesNotContain("RestTemplate");
                if (owner.startsWith("reactor.core.publisher.")) {
                    assertThat(name).as(call.getDescription()).doesNotStartWith("block").isNotEqualTo("subscribe");
                }
            });
            type.getDirectDependenciesFromSelf().forEach(dependency -> {
                JavaClass target = dependency.getTargetClass();
                assertThat(target.getName()).doesNotContain("RestTemplate");
                if (target.getName().startsWith("com.wealth.")) pending.add(target);
            });
        }
        assertThat(seen).contains(DemoLoginResetOrchestrator.class.getName(), DemoLoginResetClient.class.getName(),
                GatewayLoopbackTargetProvider.class.getName());
    }
}
