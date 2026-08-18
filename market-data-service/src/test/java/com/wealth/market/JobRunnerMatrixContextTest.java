package com.wealth.market;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JobRunnerMatrixContextTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            JobRunnerMatrixValidatorProcessor.class,
                            MarketDataRefreshJobRunner.class,
                            MarketDataRefreshSuspendedJobRunner.class,
                            MarketDataRepairJobRunner.class)
                    .withBean(MarketDataRefreshService.class, () -> mock(MarketDataRefreshService.class));

    @Test
    void bothAbsent_activatesNeitherRunner() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MarketDataRefreshJobRunner.class);
                    assertThat(ctx).doesNotHaveBean(MarketDataRefreshSuspendedJobRunner.class);
                    assertThat(ctx).doesNotHaveBean(MarketDataRepairJobRunner.class);
                });
    }

    @Test
    void refreshTrueRepairAbsent_activatesRefreshOnly() {
        runner.withPropertyValues("market-data.job-runner.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(MarketDataRefreshJobRunner.class);
                            assertThat(ctx).doesNotHaveBean(MarketDataRefreshSuspendedJobRunner.class);
                            assertThat(ctx).doesNotHaveBean(MarketDataRepairJobRunner.class);
                        });
    }

    @Test
    void refreshFalseRepairAbsent_activatesSuspendedOnly() {
        runner.withPropertyValues("market-data.job-runner.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(MarketDataRefreshJobRunner.class);
                            assertThat(ctx).hasSingleBean(MarketDataRefreshSuspendedJobRunner.class);
                            assertThat(ctx).doesNotHaveBean(MarketDataRepairJobRunner.class);
                        });
    }

    @Test
    void refreshAbsentRepairTrue_activatesRepairOnly() {
        runner.withPropertyValues("market-data.repair.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(MarketDataRefreshJobRunner.class);
                            assertThat(ctx).doesNotHaveBean(MarketDataRefreshSuspendedJobRunner.class);
                            assertThat(ctx).hasSingleBean(MarketDataRepairJobRunner.class);
                        });
    }

    @Test
    void refreshFalseAndRepairTrue_failsStartup() {
        runner.withPropertyValues(
                        "market-data.job-runner.enabled=false", "market-data.repair.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(ctx.getStartupFailure())
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("Invalid market-data job runner combination");
                        });
    }

    @Test
    void refreshTrueAndRepairTrue_failsStartup() {
        runner.withPropertyValues(
                        "market-data.job-runner.enabled=true", "market-data.repair.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
