package com.wealth.market.repair;

/**
 * Test-only interceptors. Production uses a no-op instance.
 */
public final class RepairHooks {

    public Runnable afterClaim = RepairHooks::noop;
    public Runnable afterFence = RepairHooks::noop;
    public Runnable beforeDestinationMutate = RepairHooks::noop;
    public Runnable afterDestinationWrite = RepairHooks::noop;
    public Runnable afterArchivePending = RepairHooks::noop;
    public Runnable afterSourceDelete = RepairHooks::noop;
    public Runnable beforeArchiveCommit = RepairHooks::noop;
    public Runnable afterArchiveCommit = RepairHooks::noop;

    public static RepairHooks abortAfter(String phase) {
        RepairHooks hooks = new RepairHooks();
        Runnable abort = () -> {
            throw new RepairAbortedException(phase);
        };
        switch (phase) {
            case "afterClaim" -> hooks.afterClaim = abort;
            case "afterFence" -> hooks.afterFence = abort;
            case "beforeDestinationMutate" -> hooks.beforeDestinationMutate = abort;
            case "afterDestinationWrite" -> hooks.afterDestinationWrite = abort;
            case "afterArchivePending" -> hooks.afterArchivePending = abort;
            case "afterSourceDelete" -> hooks.afterSourceDelete = abort;
            case "beforeArchiveCommit" -> hooks.beforeArchiveCommit = abort;
            case "afterArchiveCommit" -> hooks.afterArchiveCommit = abort;
            default -> throw new IllegalArgumentException(phase);
        }
        return hooks;
    }

    private static void noop() {}
}
