package com.wealth.market;

import com.wealth.market.repair.MongoMmNsRepairService;
import com.wealth.market.repair.RepairOutcome;
import com.wealth.market.repair.RepairResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataRepairJobRunnerTest {

    private GenericApplicationContext context;
    private MongoMmNsRepairService repairService;
    private MarketDataRepairJobRunner runner;
    private AtomicInteger capturedExitCode;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.refresh();
        repairService = mock(MongoMmNsRepairService.class);
        runner = new MarketDataRepairJobRunner(repairService, context);
        capturedExitCode = new AtomicInteger(-1);
        runner.exitHandler = capturedExitCode::set;
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void exitsZeroOnComplete() {
        when(repairService.run()).thenReturn(new RepairResult(RepairOutcome.COMPLETE, 1L));
        runner.run();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void exitsZeroOnAlreadyComplete() {
        when(repairService.run()).thenReturn(new RepairResult(RepairOutcome.ALREADY_COMPLETE, 1L));
        runner.run();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void exitsNonZeroOnFailedConflict() {
        when(repairService.run()).thenReturn(new RepairResult(RepairOutcome.FAILED_CONFLICT, 1L));
        runner.run();
        assertThat(capturedExitCode.get()).isNotZero();
    }
}
