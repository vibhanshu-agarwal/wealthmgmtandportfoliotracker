package com.wealth.market.repair;

/**
 * Thrown from {@link RepairHooks} to freeze a durable partial state for crash/retry tests.
 */
public class RepairAbortedException extends RuntimeException {

    public RepairAbortedException(String phase) {
        super("repair aborted at " + phase);
    }
}
