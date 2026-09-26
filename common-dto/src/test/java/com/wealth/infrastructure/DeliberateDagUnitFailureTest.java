package com.wealth.infrastructure;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * Scratch-only fixture. Exactly one unit test fails so pull-request CI can show
 * that pact-consumer still runs after a unit-tests failure. Never merge.
 */
class DeliberateDagUnitFailureTest {

    @Test
    void deliberateUnitFailureForDagProof() {
        fail("intentional unit-test failure for CI DAG de-serialization proof; do not merge");
    }
}
