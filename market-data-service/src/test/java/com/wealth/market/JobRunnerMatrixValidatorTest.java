package com.wealth.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRunnerMatrixValidatorTest {

    @Test
    void bothAbsent_isValid() {
        assertThatCode(() -> JobRunnerMatrixValidator.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    void refreshTrueRepairAbsent_isValid() {
        assertThatCode(() -> JobRunnerMatrixValidator.validate(true, null)).doesNotThrowAnyException();
    }

    @Test
    void refreshFalseRepairAbsent_isValid() {
        assertThatCode(() -> JobRunnerMatrixValidator.validate(false, null)).doesNotThrowAnyException();
    }

    @Test
    void refreshAbsentRepairTrue_isValid() {
        assertThatCode(() -> JobRunnerMatrixValidator.validate(null, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "refresh={0} repair={1} must fail startup")
    @CsvSource({
        "true, true",
        "true, false",
        "false, true",
        "false, false"
    })
    void explicitCombinationsFailStartup(boolean refresh, boolean repair) {
        assertThatThrownBy(() -> JobRunnerMatrixValidator.validate(refresh, repair))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid market-data job runner combination")
                .hasMessageContaining("fails startup rather than resolving by precedence");
    }

    @Test
    void repairFalseWithRefreshAbsent_failsStartup() {
        assertThatThrownBy(() -> JobRunnerMatrixValidator.validate(null, false))
                .isInstanceOf(IllegalStateException.class);
    }
}
