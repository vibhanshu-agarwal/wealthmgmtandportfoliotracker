package com.wealth.market.repair;

public record RepairResult(RepairOutcome outcome, long generation) {

    public int exitCode() {
        return outcome.exitCode();
    }
}
