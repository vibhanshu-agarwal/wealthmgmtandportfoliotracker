package com.wealth.gateway;

public final class ReplicaTokenTool {

    private ReplicaTokenTool() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.print("expected exactly one non-blank replica name\n");
            System.exit(2);
            return;
        }
        System.out.print(ReplicaTokenFormula.compute(args[0]) + "\n");
    }
}
